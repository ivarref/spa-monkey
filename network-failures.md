---
title: "Datomic's handling of network failures"
date: 2024-03-30T11:46:36+02:00
draft: true
---

## Introduction
This post will examine how the [Datomic on-premise peer library](https://www.datomic.com/on-prem.html)
handles and responds to network failures. The version tested is `1.0.7075`, released `2023-12-18`.

Datomic uses the [Apache Tomcat JDBC Connection Pool](https://tomcat.apache.org/tomcat-7.0-doc/jdbc-pool.html) for SQL connection management.
PostgreSQL is used as the underlying storage in this test.

More specifically we will be testing:
```
com.datomic/peer 1.0.7075 ; Released 2023-12-18
org.apache.tomcat/tomcat-jdbc 7.0.109 (bundled by datomic-pro)
org.postgresql/postgresql 42.5.1
OpenJDK 64-Bit Server VM Temurin-22+36 (build 22+36, mixed mode, sharing)
```

[//]: # (export DATOMIC_VERSION=1.0.7075)
[//]: # (wget https://datomic-pro-downloads.s3.amazonaws.com/${DATOMIC_VERSION}/datomic-pro-${DATOMIC_VERSION}.zip -O datomic-pro.zip)
[//]: # (unzip -l datomic-pro.zip | grep postg)
[//]: # (unzip -l datomic-pro.zip | grep tomcat)

We will be using [nftables](http://nftables.org/projects/nftables/index.html) to simulate network errors.

## Context

In 2021 we switched from an on premise solution to the cloud, more specifically Azure Container Instances. From time to time we would experience network issues: connections would be dropped en masse.

That it was this that was happening was not obvious at the time. We had not experienced such problems on premise. For a long time I suspected conflicts between aleph and Datomic, both of which relied on different versions of netty. We also had a Datomic query that had recently started to OOM _sometimes_. aleph/dirigiste had known [OOM problems](https://github.com/clj-commons/aleph/issues/461). Dirigiste also [swallowed exceptions](https://github.com/clj-commons/dirigiste/issues/12).

We would see requests freezing and then suddenly complete after ~16 minutes — in batch. It was chaotic. And it was all, or mostly, network issues. Hindsight is 20/20 as they say.

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
6 00:00:06 [DEBUG] {:event :kv-cluster/get-val, :val-key "6602cd9e-bb0b-4227-98f7-d7034c4de30b", :phase :begin, :pid 143279, :tid 33}
7 00:00:06 [INFO] Dropping TCP packets for 46364:5432 fd 166
8 00:00:06 [INFO] Executing sudo nft -f drop.txt ...
9 00:00:06 [INFO] Executing sudo nft -f drop.txt ... OK!
```

At line 5 we have initialized our system and are
about to perform a query using `datomic.api/q`. 
The query will trigger a database/storage read on a connection that will be
blocked.

From line 7 we can see that we're starting to drop packets
destined for PostgreSQL, which is running at port 5432.
The format used in the logs is `local-port:remote-port`.

We're starting to drop packets just before
`org.apache.tomcat.jdbc.pool.ConnectionPool/getConnection`
returns a connection, and thus also _before_ any packet is sent.
We will see later why the emphasis on before is made.

[//]: # (explain emphasis on before...)

After this we simply wait and watch for `TCP_INFO.tcpi_backoff` socket changes:
```
10 00:00:06 [INFO] client fd 166 46364:5432 initial state is {open? true,
 tcpi_rto 203333,
 tcpi_state ESTABLISHED ...}
11 00:00:06 [INFO] client fd 166 46364:5432 tcpi_backoff 0 => 1 (In 118 ms)
12 00:00:06 [INFO] client fd 166 46364:5432 tcpi_backoff 1 => 2 (In 424 ms)
13 00:00:07 [INFO] client fd 166 46364:5432 tcpi_backoff 2 => 3 (In 823 ms)
14 00:00:09 [INFO] client fd 166 46364:5432 tcpi_backoff 3 => 4 (In 1654 ms)
15 00:00:12 [INFO] client fd 166 46364:5432 tcpi_backoff 4 => 5 (In 3386 ms)
16 00:00:19 [INFO] client fd 166 46364:5432 tcpi_backoff 5 => 6 (In 6615 ms)
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

TL-DR: A change in `tcpi_backoff` means that the kernel has sent a packet,
but did not yet receive any corresponding ACK, and will thus re-send the packet.

### The kernel to the rescue

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
e.g. `sudo bash -c 'echo 6 > /proc/sys/net/ipv4/tcp_retries2'`.
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
### Finding a needle with a warning?

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
It's not very obvious what this means.

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
but _before_ a response is received?

We will introduce an in-process TCP proxy that forwards packets to and from the database.
This allows for dropping packets to the peer
upon receival of data from the database.
We've seen that the initial re-transmission timeout
is 200 ms. Waiting double this amount
should guarantee that all previous
packets have been ACK-ed before we start to drop packets.

Let find out what happens by executing `./forever.sh`:

```
1 00:00:03 [INFO] /proc/sys/net/ipv4/tcp_retries2 is 15
2 00:00:03 [INFO] PID is: 159113
3 00:00:03 [INFO] Java version is: 22
4 00:00:03 [INFO] Clear all packet filters ...
5 00:00:03 [INFO] Executing sudo nft -f accept.txt ...
6 00:00:03 [INFO] Executing sudo nft -f accept.txt ... OK!
7 00:00:03 [INFO] Starting spa-monkey on 127.0.0.1:54321
8 00:00:04 [INFO] Thread group 1 proxying new incoming connection from 54321:48044 => 37362:5432
9 00:00:04 [INFO] Thread group 2 proxying new incoming connection from 54321:48056 => 37370:5432
10 00:00:07 [INFO] Thread group 3 proxying new incoming connection from 54321:48072 => 37380:5432
11 00:00:08 [INFO] Thread group 4 proxying new incoming connection from 54321:48080 => 37390:5432
```

Our proxy is up and running at port 54321. This is also the port that
we are telling Datomic to connect to. The port mapping in the logs is
logged as `local-port:remote-port`.
Next we start a query that will
be blocked:

```
12 00:00:09 [INFO] Starting query on blocked connection ...
13 00:00:09 [DEBUG] {:event :kv-cluster/get-val, :val-key "6602cd9e-bb0b-4227-98f7-d7034c4de30b", :phase :begin, :pid 159113, :tid 61}
14 00:00:09 [INFO] ConnectionPool/getConnection returning socket 48080:54321
15 00:00:09 [INFO] client fd 177 48080:54321 initial state is {open? true, tcpi_advmss 65483, tcpi_ato 40000, tcpi_backoff 0, tcpi_ca_state 0, tcpi_fackets 0, tcpi_lost 0, tcpi_options 7, tcpi_pmtu 65535, tcpi_rcv_mss 576, tcpi_rcv_rtt 1000, tcpi_rcv_space 65495, tcpi_rcv_ssthresh 65495, tcpi_reordering 3, tcpi_retrans 0, tcpi_retransmits 0, tcpi_rto 203333, tcpi_rtt 1350, tcpi_rttvar 2644, tcpi_sacked 0, tcpi_snd_cwnd 10, tcpi_snd_mss 32768, tcpi_snd_ssthresh 2147483647, tcpi_state ESTABLISHED, tcpi_total_retrans 0, tcpi_unacked 0}
16 00:00:09 [INFO] Dropping TCP packets for 54321:48080 fd 175
...
20 00:00:09 [INFO] proxy fd 175 54321:48080 initial state is {open? true, tcpi_advmss 65483, tcpi_ato 40000, tcpi_backoff 0, tcpi_ca_state 0, tcpi_fackets 0, tcpi_lost 0, tcpi_options 7, tcpi_pmtu 65535, tcpi_rcv_mss 536, tcpi_rcv_rtt 0, tcpi_rcv_space 65483, tcpi_rcv_ssthresh 65483, tcpi_reordering 3, tcpi_retrans 0, tcpi_retransmits 0, tcpi_rto 220000, tcpi_rtt 18650, tcpi_rttvar 22457, tcpi_sacked 0, tcpi_snd_cwnd 10, tcpi_snd_mss 32768, tcpi_snd_ssthresh 2147483647, tcpi_state ESTABLISHED, tcpi_total_retrans 0, tcpi_unacked 0}
```

Notice here that we are starting two socket watchers, one for the SQL client, i.e. Datomic peer, and one for the proxy.
Please also notice that we are dropping packets coming _from_ the proxy to
the client/peer.
After this you will see the familiar tcp backoff, but this time for
the proxy side, i.e. our fake database:

```
21 00:00:09 [INFO] proxy fd 175 54321:48080 tcpi_backoff 0 => 1 (In 225 ms)
22 00:00:10 [INFO] proxy fd 175 54321:48080 tcpi_backoff 1 => 2 (In 456 ms)
23 00:00:11 [INFO] proxy fd 175 54321:48080 tcpi_backoff 2 => 3 (In 903 ms)
24 00:00:13 [INFO] proxy fd 175 54321:48080 tcpi_backoff 3 => 4 (In 1975 ms)
25 00:00:16 [INFO] proxy fd 175 54321:48080 tcpi_backoff 4 => 5 (In 3626 ms)
...
375 00:16:23 [INFO] proxy fd 175 54321:48080 tcpi_state ESTABLISHED => CLOSE (In 975120 ms)
...
377 00:16:23 [WARN] proxy fd 175 54321:48080 error in socket watcher. Message: getsockopt error: -1
378 00:16:23 [INFO] proxy fd 175 54321:48080 watcher exiting
```

So now our TCP connection, as seen by the proxy, is dropped and closed. However
the Datomic peer/client is perfectly happy and will keep waiting for the database:
```
385 00:16:57 [INFO] client fd 177 48080:54321 no changes last PT16M50.139S
386 00:16:59 [INFO] Waited for query result for PT16M51.729S
...
21733 24:00:10 [INFO] client fd 177 48080:54321 no changes last PT24H2.315S
21734 24:00:15 [INFO] Waited for query result for PT24H8.076S
```

[//]: # (note about stacktrace.)

The peer/client is still patiently waiting after 24 hours. Only a single warning will
be issued by Datomic, the familiar `datomic.future` as seen earlier in case 1:

```
{:event :datomic.future/unfilled, :seconds 180, :context {:line 168, :column 7, :file \"datomic/kv_cluster.clj\"}, :pid 159113, :tid 67}
```

Datomic Metrics Reporter will not give any hints about a stuck request.

The blocked query thread has a stacktrace like this:
```
"blocked-query-thread" #40 [159205] daemon prio=5 os_prio=0 cpu=48.29ms elapsed=86652.92s tid=0x00007f541209eab0 nid=159205 waiting on condition  [0x00007f53dd657000]
   java.lang.Thread.State: WAITING (parking)
	at jdk.internal.misc.Unsafe.park(java.base@22/Native Method)
	- parking to wait for  <0x000000061101c918> (a java.util.concurrent.FutureTask)
	at java.util.concurrent.locks.LockSupport.park(java.base@22/LockSupport.java:221)
	at java.util.concurrent.FutureTask.awaitDone(java.base@22/FutureTask.java:500)
	at java.util.concurrent.FutureTask.get(java.base@22/FutureTask.java:190)
	at clojure.core$deref_future.invokeStatic(core.clj:2317)
	at clojure.core$deref.invokeStatic(core.clj:2337)
	at clojure.core$deref.invoke(core.clj:2323)
	at clojure.core$mapv$fn__8535.invoke(core.clj:6979)
	at clojure.lang.PersistentVector.reduce(PersistentVector.java:343)
	at clojure.core$reduce.invokeStatic(core.clj:6885)
	at clojure.core$mapv.invokeStatic(core.clj:6970)
	at clojure.core$mapv.invoke(core.clj:6970)
	at datomic.common$pooled_mapv.invokeStatic(common.clj:706)
	at datomic.common$pooled_mapv.invoke(common.clj:701)
	at datomic.datalog$qmapv.invokeStatic(datalog.clj:52)
	at datomic.datalog$qmapv.invoke(datalog.clj:47)
...
	at datomic.measure.query_stats$with_phase_stats.invokeStatic(query_stats.clj:61)
	at datomic.measure.query_stats$with_phase_stats.invoke(query_stats.clj:48)
	at datomic.datalog$qsqr.invokeStatic(datalog.clj:1595)
	at datomic.datalog$qsqr.invoke(datalog.clj:1534)
	at datomic.datalog$qsqr.invokeStatic(datalog.clj:1552)
	at datomic.datalog$qsqr.invoke(datalog.clj:1534)
	at datomic.query$q_STAR_.invokeStatic(query.clj:757)
	at datomic.query$q_STAR_.invoke(query.clj:744)
	at datomic.query$q.invokeStatic(query.clj:796)
	at datomic.query$q.invoke(query.clj:793)
	at datomic.api$q.invokeStatic(api.clj:44)
	at datomic.api$q.doInvoke(api.clj:42)
	at clojure.lang.RestFn.invoke(RestFn.java:423)
	at com.github.ivarref.forever$forever$fn__7642$fn__7643.invoke(forever.clj:109)
...
```

The query will ostensibly hang forever, with
a single and somewhat obscure warning.
There is however nothing in particular stopping Datomic from doing a better job at
reporting this as an issue.

# A PostgreSQL specific quick fix

It is possible to instruct the PostgreSQL driver to time out on reads.
This is done by specifying `socketTimeout=<value_in_seconds>`
in the connection string. Quoting from the [PGProperty](https://jdbc.postgresql.org/documentation/publicapi/org/postgresql/PGProperty.html#SOCKET_TIMEOUT) documentation:

> The timeout value used for socket read operations. If reading from the server takes longer than this value, the connection is closed. This can be used as both a brute force global query timeout and a method of detecting network problems. The timeout is specified in seconds and a value of zero means that it is disabled.

It's possible to re-run the tests with `env CONN_EXTRA="&socketTimeout=10"`
to see how this setting affects the total time used:

* Case 1: from 16 minutes to ~10 seconds.
* Case 2: from infinity to — you may have guessed it — ~10 seconds.

Depending on your DB setup you may go even lower than 10 seconds.

At this point I think it's safe to conclude that the new behaviour
with `socketTimeout=10` is much better than the original, default behaviour,
with very little risk added.

# Conclusion

Parts of the retry logic for Datomic up to and including `1.0.7075` is broken.
Network problems are hard to spot, and are not well handled, nor reported, by Datomic. 

## Response from Datomic support

I've informed Datomic about my findings and shared a draft version of this post. The response from Datomic support thus far is that the findings here does not constitute an error on Datomic's part. Quote from Datomic support:

> Datomic's retry is not "broken", but having no default timeout for the Postgres driver is a misconfiguration. It's certainly something we could address on the Datomic side, by providing a default timeout setting at configuration, but we have the expectation that users configure their storages and drivers correctly for their needs. This represents a conflation of timeouts and retries, the retries are fine, but the storage level timeouts are not. As stated, we could absolutely do better right now with Postgres timeouts, but as your blog post demonstrates this is solvable in user-space by configuring the drivers and storage with a default timeout.

## Cloud epilogue

The move to the cloud, Azure Container Instances (ACI), were a relative success. The network was — and is — a mess. ACI DNS is a mess. In fact it is such a mess that we wrote a service called `DNS-fixer`. It works ¯\\_(ツ)_/¯.

All services using PostgreSQL added `socketTimeout` properties. We also discovered that the built-in JVM HttpClient does not support a [timeout for reading the body of a response](https://bugs.openjdk.org/browse/JDK-8258397) properly.

We have since moved partly to Azure Container Apps. It's much better with respect to network issues.

## Thanks

Thanks to August Lilleaas, Christian Johansen, Magnar Sveen and Sigve Sjømæling Nordgaard for comments/feedback.

## Further reading
* [When TCP sockets refuse to die](https://blog.cloudflare.com/when-tcp-sockets-refuse-to-die/)
* [HikariCP Rapid Recovery](https://github.com/brettwooldridge/HikariCP/wiki/Rapid-Recovery)
