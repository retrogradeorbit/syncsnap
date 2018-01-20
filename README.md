# syncsnap

Tool to manage ZFS snapshot synchronisation. Always tries to send the least number of incremental snapshots.

## Installation

Git clone. Then build the uberjar with:

```
$ lein uberjar
```

## Usage

Run syncsnap:

    $ java -jar target/uberjar/syncsnap-0.1.0-SNAPSHOT-standalone.jar
    syncsnap efficiently keeps one machines zfs snapshots
    up to date and in sync with another machines zfs snapshots

    Usage: syncsnap [options] user@host source dest

    Options:
      -h, --help
      -p, --pull  Pull snapshots from remote host to localhost
      -P, --push  Push snapshots from localhost to remote host

    Please refer to the manual page for more information.

## Examples

```
root@vash# ssh-agent
SSH_AUTH_SOCK=/tmp/ssh-4Q3F4K3HioT9/agent.5903; export SSH_AUTH_SOCK;
SSH_AGENT_PID=5904; export SSH_AGENT_PID;
echo Agent pid 5904;
root@vash# SSH_AUTH_SOCK=/tmp/ssh-4Q3F4K3HioT9/agent.5903; export SSH_AUTH_SOCK;
root@vash# SSH_AGENT_PID=5904; export SSH_AGENT_PID;
root@vash# echo Agent pid 5904;
Agent pid 5904
root@vash# ssh-add ~user/.ssh/id_rsa
Enter passphrase for /home/user/.ssh/id_rsa:
Identity added: /home/user/.ssh/id_rsa (/home/user/.ssh/id_rsa)
root@vash# java -jar target/uberjar/syncsnap-0.1.0-SNAPSHOT-standalone.jar --pull root@192.168.92.224 tank/postgresql tank/postgresql
fetching snapshot data from root@192.168.92.224
transfering snapshot tank/postgresql@2018-01-12_16.05.01--2m ... ok
transfering snapshot tank/postgresql@2018-01-12_17.05.01--2m ... ok
transfering snapshot tank/postgresql@2018-01-12_18.05.01--2m ...
...
transfering snapshot tank/postgresql@2018-01-16_03.05.01--2m ... ok
transfering snapshot tank/postgresql@2018-01-16_04.05.01--2m ... ok
transfering snapshot tank/postgresql@2018-01-16_05.05.01--2m ... ok
transfering snapshot tank/postgresql@2018-01-16_06.05.01--2m ... ok
synchronised
root@vash# java -jar target/uberjar/syncsnap-0.1.0-SNAPSHOT-standalone.jar --pull root@192.168.92.224 tank/postgresql tank/postgresql
fetching snapshot data from root@192.168.92.224
synchronised

```

### Bugs

Many

## License

Copyright © 2018 Crispin Wellington

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
