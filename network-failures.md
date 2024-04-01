---
title: "Datomic's handling of network failures"
date: 2024-03-30T11:46:36+02:00
draft: true
---

## Introduction
This post will examine how the [Datomic on-premise peer library](https://www.datomic.com/on-prem.html)
handles and responds to network failures.

Datomic uses the [Apache Tomcat JDBC Connection Pool](https://tomcat.apache.org/tomcat-7.0-doc/jdbc-pool.html) for SQL connection management.
PostgreSQL is used as the underlying storage in this test.

More specifically we will be testing:
```
com.datomic/peer 1.0.7075 ; Released 2023-04-27
org.apache.tomcat/tomcat-jdbc 7.0.109 (bundled by datomic-pro)
org.postgresql/postgresql 42.5.1
OpenJDK 64-Bit Server VM Temurin-22+36 (build 22+36, mixed mode, sharing)
```

[//]: # (export DATOMIC_VERSION=1.0.7075)
[//]: # (wget https://datomic-pro-downloads.s3.amazonaws.com/${DATOMIC_VERSION}/datomic-pro-${DATOMIC_VERSION}.zip -O datomic-pro.zip)
[//]: # (unzip -l datomic-pro.zip | grep postg)
[//]: # (unzip -l datomic-pro.zip | grep tomcat)

We will be using [nftables](http://nftables.org/projects/nftables/index.html) to simulate network errors.

## Setup

Read this section if you want to reproduce the output of the commands.

All commands require that `./db-up.sh` is running.
Running `./db-up.sh` will start a Datomic and PostgreSQL instance locally.
The following environment variables needs to be set:

* `POSTGRES_PASSWORD`: Password to be used for PostgreSQL.

You will also want to: 
* add `/usr/bin/nft` to sudoers for your user
* or be prepared to enter your root password.

Running this code requires Linux and Java 22 or later as it uses [JEP 434: Foreign Function & Memory API](https://openjdk.org/jeps/434).

## Case 1: TCP retry saves the day

Running `./tcp-retry.sh` you will see:

```
1 00:00:03 [INFO] /proc/sys/net/ipv4/tcp_retries2 is 15
2 00:00:03 [INFO] Clear all packet filters ...
3 00:00:03 [INFO] Executing sudo nft -f accept.txt ...
4 00:00:03 [INFO] Executing sudo nft -f accept.txt ... OK!
5 00:00:06 [INFO] Starting query on blocked connection ...
6 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "6602cd9e-bb0b-4227-98f7-d7034c4de30b", :phase :begin, :pid 140891, :tid 33}
7 00:00:06 [INFO] Dropping TCP packets for 47474:5432 fd 91
8 00:00:06 [INFO] Executing sudo nft -f drop.txt ...
9 00:00:06 [INFO] Executing sudo nft -f drop.txt ... OK!
```

At line 5 we have initialized our system and are
about to perform a query using `datomic.api/q`. 
The query will trigger a database/storage read on a connection that will be
blocked.

From line 7 we can see that we're starting to drop packets
destined for PostgreSQL, which is running at port 5432.
We're starting to drop packets just before
`org.apache.tomcat.jdbc.pool.ConnectionPool/getConnection`
returns a connection, and thus also _before_ any packet is sent.
We will see later why the emphasis on before is made.

[//]: # (explain emphasis on before...)

After this we simply wait and watch for `TCP_INFO.tcpi_backoff` socket changes:
```
0010 00:00:05 [INFO] Initial state for fd 152 {open? true,
 tcpi_rto 203333,
 tcpi_state ESTABLISHED}
0011 00:00:06 [INFO] fd 152 tcpi_backoff 0 => 1 (In 192 ms)
0012 00:00:06 [INFO] fd 152 tcpi_backoff 1 => 2 (In 430 ms)
0013 00:00:07 [INFO] fd 152 tcpi_backoff 2 => 3 (In 825 ms)
0014 00:00:08 [INFO] fd 152 tcpi_backoff 3 => 4 (In 1653 ms)
0015 00:00:12 [INFO] fd 152 tcpi_backoff 4 => 5 (In 3466 ms)
0016 00:00:19 [INFO] fd 152 tcpi_backoff 5 => 6 (In 6613 ms)
0017 00:00:32 [INFO] fd 152 tcpi_backoff 6 => 7 (In 13227 ms)
0018 00:00:59 [INFO] fd 152 tcpi_backoff 7 => 8 (In 27093 ms)
...
```

### Kernel and TCP_INFO struct detour
`tcpi_backoff` is collected from [getsockopt](https://man7.org/linux/man-pages/man2/getsockopt.2.html) using `TCP_INFO`.
The [ss man page](https://man7.org/linux/man-pages/man8/ss.8.html)
gives this definition of `isck_backoff`:

> icsk_backoff used for exponential backoff re-transmission, the
actual re-transmission timeout value is icsk_rto <<
icsk_backoff

This field, `icsk_backoff`, is copied verbatim into `tcpi_backoff` in the [kernel](https://github.com/torvalds/linux/blob/5b7c4cabbb65f5c469464da6c5f614cbd7f730f2/net/ipv4/tcp.c#L3829).
`<<` is bit shift left and thus an increment of the backoff field yields
a doubling of the re-transmission timeout value.

Then there is the `isck_rto` field. `rto` stands for `Re-transmission Time Out`.
In `getsockopt` `isck_rto` is converted from [jiffies](https://man7.org/linux/man-pages/man7/time.7.html) to microseconds into the `tcpi_rto` field.
We can see that `tcpi_rto` is initialized at 203333 microseconds,
i.e. just over 200 milliseconds.
These values correspond reasonably well to the observed
durations of each transition of `tcpi_backoff` in the log:
it starts at ~200 milliseconds, then doubles, doubles again, etc..

### The connection is finally closed

Back in the console we can finally we see:

```
163 00:16:05 [INFO] client fd 166 46364:5432 tcpi_backoff 15 => 0 (In 122879 ms)
164 00:16:05 [INFO] client fd 166 46364:5432 tcpi_state ESTABLISHED => CLOSE (In 959578 ms)
165 00:16:05 [WARN] client fd 166 46364:5432 error in socket watcher. Message: getsockopt error: -1
166 00:16:05 [INFO] client fd 166 46364:5432 watcher exiting
167 00:16:05 [WARN] Unable to clear Warnings, connection will be closed.
```

After approximately 16 minutes the kernel gives up
trying to re-send our packets and waiting for the corresponding
TCP acknowledgements. The kernel then closes the connection.

It's possible to change the number of TCP retries:
e.g. `sudo bash -c 'echo 6 > /proc/sys/net/ipv4/tcp_retries2'`
If you then re-run `./tcp-retry.sh` you will see
a much shorter timeout.

The default value of `/proc/sys/net/ipv4/tcp_retries2` is 15, i.e.
an unacknowledged packet will be re-sent 15 times
before the connection is considered broken
and then closed by the kernel.
From the [kernel ip-sysctl documentation](https://www.kernel.org/doc/Documentation/networking/ip-sysctl.txt):

> The default value of 15 yields a hypothetical timeout of 924.6 seconds and is a lower bound for the effective timeout.  TCP will effectively time out at the first RTO (Re-transmission Time Out) which exceeds the hypothetical timeout.

In our case the timeout took ~960 seconds.

After the connection is closed by the kernel,
Datomic finally retries fetching the data:

```
167 00:16:05 [WARN] Unable to clear Warnings, connection will be closed.
168 00:16:05 [INFO] {:event :kv-cluster/retry, :StorageGetBackoffMsec 0, :attempts 0, :max-retries 9, :cause "java.net.SocketException", :pid 143279, :tid 33}
169 00:16:05 [INFO] {:MetricsReport {:lo 1, :hi 1, :sum 1, :count 1}, :StorageGetBackoffMsec {:lo 0, :hi 0, :sum 0, :count 1}, :AvailableMB 7860.0, :ObjectCacheCount 20, :event :metrics, :pid 143279, :tid 56}
170 00:16:05 [DEBUG] {:event :metrics/report, :phase :begin, :pid 143279, :tid 56}
171 00:16:05 [DEBUG] {:event :metrics/report, :msec 0.0276, :phase :end, :pid 143279, :tid 56}
173 00:16:05 [DEBUG] {:event :kv-cluster/get-val, :val-key "6602cd9e-bb0b-4227-98f7-d7034c4de30b", :msec 960000.0, :phase :end, :pid 143279, :tid 33}
174 00:16:05 [DEBUG] {:event :kv-cluster/get-val, :val-key "6602cd9e-23a5-4d99-bf73-b8badbe60495", :phase :begin, :pid 143279, :tid 33}
176 00:16:05 [DEBUG] {:event :kv-cluster/get-val, :val-key "6602cd9e-23a5-4d99-bf73-b8badbe60495", :msec 2.09, :phase :end, :pid 143279, :tid 33}
177 00:16:05 [DEBUG] {:event :kv-cluster/get-val, :val-key "6602cd9e-6347-4910-9d91-93501966849a", :phase :begin, :pid 143279, :tid 33}
179 00:16:05 [DEBUG] {:event :kv-cluster/get-val, :val-key "6602cd9e-6347-4910-9d91-93501966849a", :msec 1.63, :phase :end, :pid 143279, :tid 33}
180 00:16:06 [INFO] Query on blocked connection ... Done in 00:15:59 aka 959937 milliseconds
```
### A needle with a warning

There are a few things to note here.
One is that there is only two warnings.

One was issued by `org.apache.tomcat.jdbc.pool.PooledConnection`.
Its message reads:
`Unable to clear Warnings, connection will be closed.`

This is the by far the best indication we get
that something went wrong.
And this message is only included because the
[jul to slf4j bridge](https://stackoverflow.com/questions/9117030/jul-to-slf4j-bridge) was installed,
otherwise this message would only have made it to stdout,
which you may or may not be collecting in your logging
infrastructure.

The other warning is issued by `datomic.future`,
and reads
`{:event :datomic.future/unfilled, :seconds 180, :context {:line 168, :column 7, :file "datomic/kv_cluster.clj"}, :pid 143279, :tid 62}`.


Datomic also report that `:StorageGetMsec` had a `:hi[gh]`
of `960000`, i.e. around 16 minutes. 
This is logged at an INFO-level, making it hard
to spot.

In summary: a network issue like this
is rather hard to both spot and troubleshoot when using Datomic.

## Case 2: a query that hangs forever?

In case 1 we saw what happened when the TCP send buffer had unacknowledged data on a dropped connection: 
the kernel saved us and Datomic successfully retried the query, albeit taking ~16 minutes.

What happens if the connection becomes blocked _after_ the send buffer is acknowledged,
but before a response is received?

We will introduce an in-process TCP proxy that forwards packets to and from the database.
This allows for dropping packets to the peer
upon receival of data from the database.
We've seen that the initial re-transmission timeout
is 200 ms. Waiting double this amount
should guarantee that all previous
packets have been ACK-ed before we start to drop packets.

Let find out what happens by executing `./forever.sh`:

```
0001 00:00:03 [INFO] /proc/sys/net/ipv4/tcp_retries2 is 6
0002 00:00:03 [INFO] Clear all packet filters ...
0003 00:00:03 [INFO] Executing sudo nft -f accept.txt ...
0004 00:00:03 [INFO] Executing sudo nft -f accept.txt ... OK!
0005 00:00:03 [INFO] Starting spa-monkey on 127.0.0.1:54321
0006 00:00:04 [INFO] Thread group 1 proxying new incoming connection from 54321:44746 => 57062:5432
0007 00:00:04 [INFO] Thread group 2 proxying new incoming connection from 54321:44756 => 57068:5432
0008 00:00:08 [INFO] Thread group 3 proxying new incoming connection from 54321:44760 => 57074:5432
0009 00:00:08 [INFO] Thread group 4 proxying new incoming connection from 54321:44768 => 57082:5432
```

Our proxy is up and running at port 54321. This is also the port that
we are telling Datomic to connect to. Then we start a query that will
be blocked:

```
0010 00:00:08 [INFO] Starting query on blocked connection ...
0012 00:00:08 [INFO] ConnectionPool/getConnection returning socket 44746:54321
0013 00:00:08 [INFO] client fd 82 44746:54321 initial state is {open? true, tcpi_advmss 65483, tcpi_ato 40000, tcpi_backoff 0, tcpi_ca_state 0, tcpi_fackets 0, tcpi_lost 0, tcpi_options 7, tcpi_pmtu 65535, tcpi_rcv_mss 576, tcpi_rcv_rtt 1000, tcpi_rcv_space 65495, tcpi_rcv_ssthresh 65495, tcpi_reordering 3, tcpi_retrans 0, tcpi_retransmits 0, tcpi_rto 203333, tcpi_rtt 1279, tcpi_rttvar 2395, tcpi_sacked 0, tcpi_snd_cwnd 10, tcpi_snd_mss 32768, tcpi_snd_ssthresh 2147483647, tcpi_state ESTABLISHED, tcpi_total_retrans 0, tcpi_unacked 0}
0014 00:00:09 [INFO] Dropping TCP packets for 54321:44746 fd 81
...
0018 00:00:09 [INFO] proxy fd 81 54321:44746 initial state is {open? true, tcpi_advmss 65483, tcpi_ato 40000, tcpi_backoff 0, tcpi_ca_state 0, tcpi_fackets 0, tcpi_lost 0, tcpi_options 7, tcpi_pmtu 65535, tcpi_rcv_mss 536, tcpi_rcv_rtt 0, tcpi_rcv_space 65483, tcpi_rcv_ssthresh 65483, tcpi_reordering 3, tcpi_retrans 0, tcpi_retransmits 0, tcpi_rto 216666, tcpi_rtt 16117, tcpi_rttvar 21442, tcpi_sacked 0, tcpi_snd_cwnd 10, tcpi_snd_mss 32768, tcpi_snd_ssthresh 2147483647, tcpi_state ESTABLISHED, tcpi_total_retrans 0, tcpi_unacked 0}
```

Notice here that we are starting two socket watchers, one for the SQL client
and one for the proxy.
Please also notice that we are dropping packets coming _from_ the proxy to
the client.
After this you will see the familiar tcp backoff, but this time for
the proxy side, i.e. our fake database:

```
0019 00:00:09 [INFO] proxy fd 81 54321:44746 tcpi_backoff 0 => 1 (In 241 ms)
0020 00:00:10 [INFO] proxy fd 81 54321:44746 tcpi_backoff 1 => 2 (In 449 ms)
0021 00:00:11 [INFO] proxy fd 81 54321:44746 tcpi_backoff 2 => 3 (In 879 ms)
0022 00:00:12 [INFO] proxy fd 81 54321:44746 tcpi_backoff 3 => 4 (In 1868 ms)
...
0030 00:00:38 [INFO] proxy fd 81 54321:44746 tcpi_state ESTABLISHED => CLOSE (In 29464 ms)
...
0035 00:00:38 [INFO] proxy fd 81 54321:44746 watcher exiting
```

So now our TCP connection, as seen by the proxy, is dropped and closed. However
the Datomic SQL client is perfectly happy:
```
00:01:58 [INFO] client fd 82 44746:54321 no changes last PT1M50.016S
...
24:00:10 [INFO] client fd 82 44746:54321 no changes last PT24H1.11S
```

The peer or SQL client is still patiently waiting after 24 hours, and no warning nor error is issued
by Datomic:

```
$ cat logs/forever.log.json | jq -r -c 'select( .level == "WARN" or .level == "ERROR" ) | .uptime + " " + .level + " " + .logger + " " + .message'
00:00:38 WARN com.github.ivarref.spa-monkey Thread group 1 :send src 54321:44746 => 57062:5432 Exception while reading socket: Connection timed out of type java.net.SocketException
00:00:38 WARN com.github.ivarref.spa-monkey Thread group 1 :recv src 57062:5432 => 54321:44746 Exception while reading socket: Socket closed of type java.net.SocketException
00:00:38 WARN com.github.ivarref.utils proxy fd 81 54321:44746 error in socket watcher. Message: getsockopt error: -1
```

Datomic Metrics Reporter does not give any hints about a stuck request either:
```
$ cat logs/forever.log.json | jq -r -c 'select( .thread == "'"Datomic Metrics Reporter"'") | .message' | grep "{:MetricsReport" | sort | uniq
{:MetricsReport {:lo 1, :hi 1, :sum 1, :count 1}, :AvailableMB 7760.0, :ObjectCacheCount 20, :event :metrics, :pid 439611, :tid 54}
... (Variations on :AvailableMB elided.)
```


## Case 3: a partially bricked application?

We've seen in case 2 that the default PostgreSQL driver and TCP stack is happy to wait
forever for a packet. Datomic does not improve on this situation.

How does such a situation affect the rest of the application?
We will now issue one query that will be dropped, and then issue more queries
in a different thread while the dropped query is running.


```

"pull-demo-1" #59 [129516] daemon prio=5 os_prio=0 cpu=16.32ms elapsed=30.06s tid=0x00007f7b8c6e3fb0 nid=129516 waiting on condition  [0x00007f7bcd3e1000]
   java.lang.Thread.State: WAITING (parking)

"pull-demo-2" #71 [129538] daemon prio=5 os_prio=0 cpu=47.34ms elapsed=28.37s tid=0x00007f7b5c001d30 nid=129538 waiting for monitor entry  [0x00007f7bcc1fd000]
   java.lang.Thread.State: BLOCKED (on object monitor)

```
# A quick fix

It is possible to instruct the PostgreSQL driver to time out on reads.
This is done by specifying `socketTimeout=<value_in_seconds>`
in the connection string. Quoting from the [PGProperty](https://jdbc.postgresql.org/documentation/publicapi/org/postgresql/PGProperty.html#SOCKET_TIMEOUT) documentation:

> The timeout value used for socket read operations. If reading from the server takes longer than this value, the connection is closed. This can be used as both a brute force global query timeout and a method of detecting network problems. The timeout is specified in seconds and a value of zero means that it is disabled.

It's possible to re-run the tests with `CONN_EXTRA="&socketTimeout=10"`
to see how this setting affects the total time used:

* Case 1: from 48 minutes to 1 minute.
* Case 2: from infinity to 10.2 seconds.

Depending on your DB setup you may go even lower than 10 seconds.

At this point I think it's safe to conclude that the new behaviour
with `socketTimeout=10` is much better than the original default behaviour,
with very little/no risk added.

## Further reading
[When TCP sockets refuse to die](https://blog.cloudflare.com/when-tcp-sockets-refuse-to-die/)
