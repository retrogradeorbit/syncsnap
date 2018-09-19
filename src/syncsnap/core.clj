(ns syncsnap.core
  (:require [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [clj-time.core :as t]
            [clj-time.format :as f])
  (:gen-class))

(def cli-options
  [["-h" "--help"]
   ["-i" "--identity PRIVATE_KEY_FILE" "use the specified ssh private key to connect"
    :validate [#(.exists (io/file %)) "Key file does not exist"]]
   ["-p" "--port PORT" "connect to ssh on the specified port"
    :default 22
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]
    ]
   ["-l" "--pull" "Pull snapshots from remote host to localhost"]
   ["-s" "--push" "Push snapshots from localhost to remote host"]
   #_ ["-s"  "--skip-gaps" "If a more recent snapshot is in the destination, do not transfer the snapshot"]
   #_ ["-D"  "--delete-missing" "If a snapshot is in the destination and is missing on the source, delete it from the destination"]
   #_ [nil  "--poll" "Keep running and continually poll the machines for new snapshots"]
   #_ [nil  "--poll-delay DELAY" "number of seconds to wait between polling the remote snapshots (default 1 hour)"
    :default (* 60 60)]
   ])

(defn usage [options-summary]
  (->> ["syncsnap efficiently keeps one machines zfs snapshots"
        "up to date and in sync with another machines zfs snapshots"
        ""
        "Usage: syncsnap [options] user@host source dest"
        ""
        "Options:"
        options-summary
        ""
        "Please refer to the manual page for more information."]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}

      ;; custom validation on arguments
      (:pull options)
      {:action :pull :args arguments :opts options}

      (:push options)
      {:action :push :args arguments :opts options}

      :else ; failed custom validation => exit with usage summary
      {:exit-message (usage summary)})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn line-to-hash [[snap weekday month day time year compress dedup]]
  (let [[dataset tag] (string/split snap #"@")
        timestamp (str year "-" month "-" day "-" time)
        pattern "yyyy-MMM-dd-HH:mm"
        custom-formatter (f/formatter pattern)
        ]
    {:dataset dataset
     :tag tag
     :timestamp (f/parse custom-formatter timestamp)
     :compress (when-not (= "-" compress) compress)
     :dedup (when-not (= "-" dedup) dedup)}))

(defn make-tag [dataset tag]
  (str dataset "@" tag))

(defn quote-str [s]
  (if (#{"|" ">" "<"} s)
    s
    (str "\""
         (string/replace s #"\"" "\\\\\"")
         "\"")))

(defn ssh [host opts args]
  (let [args (-> ["ssh"
                  (when (:identity opts) ["-i" (:identity opts)])
                  (when (:port opts) ["-p" (str (:port opts))])
                  host]
                 flatten
                 (concat args))
        {:keys [out err exit]} (apply sh args)]
    (assert (zero? exit) (str "command failed: " args " error: " err))
    out))

(defn zfs-list
  ([host]
   (-> (sh "ssh" host "zfs" "list" "-t" "snapshot" "-o" "name,creation,compression,dedup")
       :out
       (string/split #"\n")
       next
       (->> (map #(string/split % #"\s+"))
            (map line-to-hash)
            (into []))))
  ([]
   (-> (sh "zfs" "list" "-t" "snapshot" "-o" "name,creation,compression,dedup")
       :out
       (string/split #"\n")
       next
       (->> (map #(string/split % #"\s+"))
            (map line-to-hash)
            (into [])))))

(defmulti zfs-transfer (fn [direction src-host src dest-host dest tag-1 tag-2] direction))

(defmethod zfs-transfer :pull [_ src-host src _ dest tag-1 tag-2]
  (if tag-1
    ;; incremental
    (sh "bash" "-c"
        (str "ssh " src-host " zfs send -i " (make-tag src tag-1) " " (make-tag src tag-2)
             " | zfs recv " dest))
    ;; full snapshot
    (sh "bash" "-c"
        (str "ssh " src-host " zfs send " (make-tag src tag-2)
             " | zfs recv " dest)))
  )

(defmethod zfs-transfer :push [_ src-host src dest-host dest tag-1 tag-2]
  (if tag-1
    ;; incremental
    (do (println (str "zfs send -i " (make-tag src tag-1) " " (make-tag src tag-2)
               " | ssh " dest-host " zfs recv " dest ;;"@" (make-tag dest tag-2)
               ))
      (sh "bash" "-c"
          (str "zfs send -i " (make-tag src tag-1) " " (make-tag src tag-2)
               " | ssh " dest-host " zfs recv -F " dest ;;"@" (make-tag dest tag-2)
               )))
    ;; full snapshot
    (sh "bash" "-c"
        (str "zfs send " (make-tag src tag-2)
             " | ssh " dest-host " zfs recv -F " dest ;;"@" (make-tag dest tag-2)
             ))))


(defmulti zfs-synchronise (fn [action host src dest] action))

(defmethod zfs-synchronise :pull [_ host src dest]
  (println "fetching snapshot data from" host)
  (let [remote-snaps (->> (zfs-list host)
                          (sort-by :timestamp)
                          (filter #(-> % :dataset (= src))))
        local-snaps (->> (zfs-list)
                         (sort-by :timestamp)
                         (filter #(-> % :dataset (= dest))))
        local-snap-tags (->> local-snaps
                             (map :tag)
                             (into #{}))]
    (loop [{:keys [tag] :as snap} (first remote-snaps)
           remain (rest remote-snaps)
           last-snap nil]
      (when-not (local-snap-tags tag)
        (print "transfering snapshot" (make-tag src tag) "... ")
        (flush)
        (let [{:keys [out exit err] :as res}
              (if last-snap
                (zfs-transfer :pull host src nil dest (:tag last-snap) tag)
                (zfs-transfer :pull host src nil dest nil tag))]
          (if (zero? exit)
            (println "ok")
            (do
              (println "error! " err)
              (System/exit 1)))
          ))
      (when-not (empty? remain)
        (recur (first remain) (rest remain) snap)))))

(defmethod zfs-synchronise :push [_ host src dest]
  (println "fetching snapshot data from" host)
  (let [remote-snaps (->> (zfs-list host)
                          (sort-by :timestamp)
                          (filter #(-> % :dataset (= dest))))
        remote-snap-tags (->> remote-snaps
                             (map :tag)
                             (into #{}))
        local-snaps (->> (zfs-list)
                         (sort-by :timestamp)
                         (filter #(-> % :dataset (= src))))
        local-snap-tags (->> local-snaps
                             (map :tag)
                             (into #{}))]
    (loop [{:keys [tag] :as snap} (first local-snaps)
           remain (rest local-snaps)
           last-snap nil]
      (when-not (remote-snap-tags tag)
        (print "transfering snapshot" (make-tag src tag) "... ")
        (flush)
        (let [{:keys [out exit err] :as res}
              (if last-snap
                (zfs-transfer :push nil src host dest (:tag last-snap) tag)
                (zfs-transfer :push nil src host dest nil tag))]
          (if (zero? exit)
            (println "ok")
            (do
              (println "error! " err)
              (System/exit 1)))
          ))
      (when-not (empty? remain)
        (recur (first remain) (rest remain) snap)))))

(defn -main [& args]
  (let [{:keys [action options args exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (apply zfs-synchronise action args))
    (println "synchronised")
    (System/exit 0)))
