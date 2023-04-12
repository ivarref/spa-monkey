---
title: "Datomic's handling of network failures — and how to improve it"
date: 2022-09-16T11:46:36+02:00
draft: true
---

## Introduction
This post will examine how the [Datomic on-premise peer library](https://www.datomic.com/on-prem.html)
handles and responds to network failures.

Datomic uses the [Apache Tomcat JDBC Connection Pool](https://tomcat.apache.org/tomcat-7.0-doc/jdbc-pool.html) for connection management.
PostgreSQL is used as the underlying storage.

More specifically we will be testing:
```
com.datomic/datomic-pro 1.0.6527 ; Released 2022-10-21
org.apache.tomcat/tomcat-jdbc 7.0.109 (bundled by datomic-pro)
org.postgresql/postgresql 42.5.0
OpenJDK 64-Bit Server VM (build 20-ea+34-2340, mixed mode, sharing)
```

We will be using [nftables](http://nftables.org/projects/nftables/index.html) to simulate network errors.

## Setup

Read this section if you want to reproduce the output of the commands.

All commands require that `./db-up.sh` is running.
Running `./db-up.sh` will start a Datomic and PostgreSQL instance locally.
The following environment variables needs to be set:

* `DATOMIC_HTTP_USERNAME`: Username used to fetch datomic on prem transactor zip file.
* `DATOMIC_HTTP_PASSWORD`: Password used to fetch datomic on prem transactor zip file.
* `DATOMIC_LICENSE_KEY`: Datomic license key.
* `POSTGRES_PASSWORD`: Password to be used for PostgreSQL.

You will also want to: 
* be prepared to enter your root password,
* add `/usr/bin/nft` to sudoers for your user
* or run the scripts using `sudo -E`.

Running this code requires running Linux and  
Java 20 or later as it uses [JEP 434: Foreign Function & Memory API](https://openjdk.org/jeps/434).

## Case 1: TCP retry saves the day

Running `sudo -E ./tcp-retry.sh` you will see:

```
0001 00:00:03 [INFO] /proc/sys/net/ipv4/tcp_retries2 is 15
0002 00:00:03 [INFO] Clear all packet filters ...
0003 00:00:03 [INFO] Executing sudo nft -f accept.txt ...
0004 00:00:03 [INFO] Executing sudo nft -f accept.txt ... OK!
0005 00:00:05 [INFO] Starting query on blocked connection ...
0006 00:00:05 [DEBUG] CLI-agent-send-off-pool-3 datomic.kv-cluster {:event :kv-cluster/get-val, :val-key "63f626cd-c6ef-4649-9fbd-979acc8dcd45", :phase :begin, :pid 382404, :tid 59}
0007 00:00:05 [INFO] Dropping TCP packets for 127.0.0.1:45492->127.0.0.1:5432 fd 152
0008 00:00:05 [INFO] Executing sudo nft -f drop.txt ...
0009 00:00:05 [INFO] Executing sudo nft -f drop.txt ... OK!
...
```

At line 5 we have initialized our system and are
about to perform a query using `datomic.api/q`. 
The query will trigger a database/storage read on a connection that will be
blocked.

From line 7 we can see that we're starting to drop packets
destined for PostgreSQL, which is running at port 5432.
We're starting to drop packets just before
`org.apache.tomcat.jdbc.pool.ConnectionPool/getConnection`
returns a connection, and thus also before any packet is sent.
After this we simply wait and watch for TCP_INFO socket changes:
```
0010 00:00:05 [INFO] Initial state for fd 152 {open? true, tcpi_advmss 65483, tcpi_ato 40000, tcpi_backoff 0, tcpi_ca_state 0, tcpi_fackets 0, tcpi_last_ack_recv 120, tcpi_last_ack_sent 0, tcpi_last_data_recv 120, tcpi_last_data_sent 0, tcpi_lost 0, tcpi_options 7, tcpi_pmtu 65535, tcpi_probes 0, tcpi_rcv_mss 577, tcpi_rcv_rtt 1000, tcpi_rcv_space 65495, tcpi_rcv_ssthresh 65495, tcpi_reordering 3, tcpi_retrans 0, tcpi_retransmits 0, tcpi_rto 203333, tcpi_rtt 200, tcpi_rttvar 110, tcpi_sacked 0, tcpi_snd_cwnd 10, tcpi_snd_mss 32768, tcpi_snd_ssthresh 2147483647, tcpi_state 1, tcpi_state_str ESTABLISHED, tcpi_total_retrans 0, tcpi_unacked 0}
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

`tcpi_backoff` is collected from [getsockopt](https://man7.org/linux/man-pages/man2/getsockopt.2.html) using `TCP_INFO`.
The [ss man page](https://man7.org/linux/man-pages/man8/ss.8.html)
gives this definition of `isck_backoff`:

> icsk_backoff used for exponential backoff re-transmission, the
actual re-transmission timeout value is icsk_rto <<
icsk_backoff

This field, `iscv_backoff`, is copied verbatim into `tcpi_backoff` in the [kernel](https://github.com/torvalds/linux/blob/5b7c4cabbb65f5c469464da6c5f614cbd7f730f2/net/ipv4/tcp.c#L3829).

The `isck_rto` field is handled differently. `rto` stands for `Re-transmission Time Out`.
In `getsockopt` `isck_rto` is converted from [jiffies](https://man7.org/linux/man-pages/man7/time.7.html) to microseconds into the `tcpi_rto` field.
We can see that `tcpi_rto` is initialized at 203333 microseconds,
i.e. just over 200 milliseconds.
These values correspond reasonably well to the observed
durations of each transition of `tcpi_backoff` in the log:
it starts at ~200 milliseconds, then doubles, doubles again, etc..

Then finally we see:

```
0087 00:15:53 [WARN] CLI-agent-send-off-pool-3 org.apache.tomcat.jdbc.pool.PooledConnection Unable to clear Warnings, connection will be closed.
0088 00:15:53 [INFO] CLI-agent-send-off-pool-3 datomic.kv-cluster {:event :kv-cluster/retry, :StorageGetBackoffMsec 0, :attempts 0, :max-retries 9, :cause "java.net.SocketException", :pid 382404, :tid 59}
```

After approximately 16 minutes the kernel gives up
trying to re-send our packets and waiting for the corresponding
TCP acknowledgements. The kernel then closes the connection.

It's possible to change the number of TCP retries:
e.g. `sudo bash -c 'echo 6 > /proc/sys/net/ipv4/tcp_retries2'`
If you then re-run `./tcp-retry.sh` you will see
a much shorter timeout.

The default value of `/proc/sys/net/ipv4/tcp_retries2` is 15, i.e.
an unacknowledged packet is re-sent 15 times
before the connection is considered broken
and then closed by the kernel.
From the [kernel ip-sysctl documentation](https://www.kernel.org/doc/Documentation/networking/ip-sysctl.txt):

> The default value of 15 yields a hypothetical timeout of 924.6 seconds and is a lower bound for the effective timeout.  TCP will effectively time out at the first RTO (Re-transmission Time Out) which exceeds the hypothetical timeout.

In our case the timeout took ~950 seconds.

After the connection is closed by the kernel,
Datomic finally retries fetching the data:

```
0088 00:15:53 [INFO] CLI-agent-send-off-pool-3 datomic.kv-cluster {:event :kv-cluster/retry, :StorageGetBackoffMsec 0, :attempts 0, :max-retries 9, :cause "java.net.SocketException", :pid 382404, :tid 59}
0089 00:15:53 [INFO] Not dropping anything for 127.0.0.1:38848->127.0.0.1:5432
0090 00:15:53 [DEBUG] CLI-agent-send-off-pool-3 datomic.kv-cluster {:event :kv-cluster/get-val, :val-key "63f626cd-c6ef-4649-9fbd-979acc8dcd45", :msec 948000.0, :phase :end, :pid 382404, :tid 59}
0091 00:15:53 [DEBUG] CLI-agent-send-off-pool-3 datomic.kv-cluster {:event :kv-cluster/get-val, :val-key "63f626cd-d473-4261-a4ca-2f22342817fb", :phase :begin, :pid 382404, :tid 59}
0092 00:15:53 [INFO] Not dropping anything for 127.0.0.1:38848->127.0.0.1:5432
0093 00:15:53 [DEBUG] CLI-agent-send-off-pool-3 datomic.kv-cluster {:event :kv-cluster/get-val, :val-key "63f626cd-d473-4261-a4ca-2f22342817fb", :msec 2.47, :phase :end, :pid 382404, :tid 59}
0094 00:15:53 [DEBUG] CLI-agent-send-off-pool-3 datomic.kv-cluster {:event :kv-cluster/get-val, :val-key "63f626cd-868b-4247-8f6e-5cae524c712a", :phase :begin, :pid 382404, :tid 59}
0095 00:15:53 [INFO] Not dropping anything for 127.0.0.1:38848->127.0.0.1:5432
0096 00:15:53 [DEBUG] CLI-agent-send-off-pool-3 datomic.kv-cluster {:event :kv-cluster/get-val, :val-key "63f626cd-868b-4247-8f6e-5cae524c712a", :msec 1.53, :phase :end, :pid 382404, :tid 59}
0097 00:15:53 [INFO] Query on blocked connection ... Done in 00:15:47 aka 947996 milliseconds
```

There are a few things to note here.
One is that there is only a single warning, which
was issued by `org.apache.tomcat.jdbc.pool.PooledConnection`.
Its message reads:
`Unable to clear Warnings, connection will be closed.`

This is the by far the best indication we get
that something went wrong.
And this message is only included because the
[jul to slf4j bridge](https://stackoverflow.com/questions/9117030/jul-to-slf4j-bridge) was installed,
otherwise this message would only have made it to stdout,
which you may or may not be collecting into your logging
infrastructure.

Despite the fact that the query took approximately 16
minutes, Datomic does not give any warning itself.
It does however report that `:StorageGetMsec` had a `:hi[gh]`
of `949000`, i.e. around 16 minutes. 
This is logged at an INFO-level, making it hard
to spot.

In summary: a network issue like this
is rather hard to both spot and troubleshoot using Datomic.

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

The peer or SQL client is still waiting after 24 hours, and no warning nor error is issued
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

If we peek at the JVM stacktrace using `kill -3 <java-pid>`, we will see something
like this:
```
"main" #1 [536663] prio=5 os_prio=0 cpu=4394.78ms elapsed=56.83s tid=0x00007f177002b780 nid=536663 waiting on condition  [0x00007f1774f3a000]
   java.lang.Thread.State: WAITING (parking)
        at jdk.internal.misc.Unsafe.park(java.base@20-ea/Native Method)
        - parking to wait for  <0x000000060f002a00> (a java.util.concurrent.FutureTask)
        at java.util.concurrent.locks.LockSupport.park(java.base@20-ea/LockSupport.java:221)
        at java.util.concurrent.FutureTask.awaitDone(java.base@20-ea/FutureTask.java:500)
        at java.util.concurrent.FutureTask.get(java.base@20-ea/FutureTask.java:190)
        at clojure.core$deref_future.invokeStatic(core.clj:2317)
        at clojure.core$deref.invokeStatic(core.clj:2337)
        at clojure.core$deref.invoke(core.clj:2323)
        at clojure.core$mapv$fn__8535.invoke(core.clj:6979)
        at clojure.lang.PersistentVector.reduce(PersistentVector.java:343)
        at clojure.core$reduce.invokeStatic(core.clj:6885)
        at clojure.core$mapv.invokeStatic(core.clj:6970)
        at clojure.core$mapv.invoke(core.clj:6970)
        at datomic.common$pooled_mapv.invokeStatic(common.clj:689)
        at datomic.common$pooled_mapv.invoke(common.clj:684)
        at datomic.datalog$qmapv.invokeStatic(datalog.clj:51)
        at datomic.datalog$qmapv.invoke(datalog.clj:46)
        at datomic.datalog$fn__6335.invokeStatic(datalog.clj:608)
        at datomic.datalog$fn__6335.invoke(datalog.clj:342)
        at datomic.datalog$fn__6191$G__6165__6206.invoke(datalog.clj:64)
        at datomic.datalog$join_project_coll.invokeStatic(datalog.clj:129)
        at datomic.datalog$join_project_coll.invoke(datalog.clj:127)
        at datomic.datalog$fn__6260.invokeStatic(datalog.clj:232)
        at datomic.datalog$fn__6260.invoke(datalog.clj:228)
        at datomic.datalog$fn__6170$G__6163__6185.invoke(datalog.clj:64)
        at datomic.datalog$eval_clause$fn__6797.invoke(datalog.clj:1385)
        at datomic.datalog$eval_clause.invokeStatic(datalog.clj:1380)
        at datomic.datalog$eval_clause.invoke(datalog.clj:1346)
        at datomic.datalog$eval_rule$fn__6823.invoke(datalog.clj:1466)
        at datomic.datalog$eval_rule.invokeStatic(datalog.clj:1451)
        at datomic.datalog$eval_rule.invoke(datalog.clj:1430)
        at datomic.datalog$eval_query.invokeStatic(datalog.clj:1494)
        at datomic.datalog$eval_query.invoke(datalog.clj:1477)
        at datomic.datalog$qsqr.invokeStatic(datalog.clj:1583)
        at datomic.datalog$qsqr.invoke(datalog.clj:1522)
        at datomic.datalog$qsqr.invokeStatic(datalog.clj:1540)
        at datomic.datalog$qsqr.invoke(datalog.clj:1522)
        at datomic.query$q_STAR_.invokeStatic(query.clj:756)
        at datomic.query$q_STAR_.invoke(query.clj:743)
        at datomic.query$q.invokeStatic(query.clj:795)
        at datomic.query$q.invoke(query.clj:792)
        at datomic.api$q.invokeStatic(api.clj:44)
        at datomic.api$q.doInvoke(api.clj:42)
        at clojure.lang.RestFn.invoke(RestFn.java:423)
        at com.github.ivarref.tcp_retry$forever.invokeStatic(tcp_retry.clj:155)
        at com.github.ivarref.tcp_retry$forever.invoke(tcp_retry.clj:111)
        at clojure.lang.AFn.applyToHelper(AFn.java:154)
        at clojure.lang.AFn.applyTo(AFn.java:144)
        at clojure.lang.Var.applyTo(Var.java:705)
        at clojure.core$apply.invokeStatic(core.clj:667)
        at clojure.core$apply.invoke(core.clj:662)
        at clojure.run.exec$exec.invokeStatic(exec.clj:48)
        at clojure.run.exec$exec.doInvoke(exec.clj:39)
        at clojure.lang.RestFn.invoke(RestFn.java:423)
        at clojure.run.exec$_main$fn__205.invoke(exec.clj:180)
        at clojure.run.exec$_main.invokeStatic(exec.clj:176)
        at clojure.run.exec$_main.doInvoke(exec.clj:139)
        at clojure.lang.RestFn.invoke(RestFn.java:397)
        at clojure.lang.AFn.applyToHelper(AFn.java:152)
        at clojure.lang.RestFn.applyTo(RestFn.java:132)
        at clojure.lang.Var.applyTo(Var.java:705)
        at clojure.core$apply.invokeStatic(core.clj:667)
        at clojure.main$main_opt.invokeStatic(main.clj:514)
        at clojure.main$main_opt.invoke(main.clj:510)
        at clojure.main$main.invokeStatic(main.clj:664)
        at clojure.main$main.doInvoke(main.clj:616)
        at clojure.lang.RestFn.applyTo(RestFn.java:137)
        at clojure.lang.Var.applyTo(Var.java:705)
        at clojure.main.main(main.java:40)

"CLI-agent-send-off-pool-7" #58 [536760] daemon prio=5 os_prio=0 cpu=14.40ms elapsed=48.94s tid=0x00007f1694001140 nid=536760 runnable  [0x00007f173d2cc000]
   java.lang.Thread.State: RUNNABLE
        at sun.nio.ch.Net.poll(java.base@20-ea/Native Method)
        at sun.nio.ch.NioSocketImpl.park(java.base@20-ea/NioSocketImpl.java:186)
        at sun.nio.ch.NioSocketImpl.park(java.base@20-ea/NioSocketImpl.java:196)
        at sun.nio.ch.NioSocketImpl.implRead(java.base@20-ea/NioSocketImpl.java:304)
        at sun.nio.ch.NioSocketImpl.read(java.base@20-ea/NioSocketImpl.java:340)
        at sun.nio.ch.NioSocketImpl$1.read(java.base@20-ea/NioSocketImpl.java:789)
        at java.net.Socket$SocketInputStream.read(java.base@20-ea/Socket.java:1025)
        at org.postgresql.core.VisibleBufferedInputStream.readMore(VisibleBufferedInputStream.java:161)
        at org.postgresql.core.VisibleBufferedInputStream.ensureBytes(VisibleBufferedInputStream.java:128)
        at org.postgresql.core.VisibleBufferedInputStream.ensureBytes(VisibleBufferedInputStream.java:113)
        at org.postgresql.core.VisibleBufferedInputStream.read(VisibleBufferedInputStream.java:73)
        at org.postgresql.core.PGStream.receiveChar(PGStream.java:453)
        at org.postgresql.core.v3.QueryExecutorImpl.processResults(QueryExecutorImpl.java:2120)
        at org.postgresql.core.v3.QueryExecutorImpl.execute(QueryExecutorImpl.java:356)
        - locked <0x00000007ff512c90> (a org.postgresql.core.v3.QueryExecutorImpl)
        at org.postgresql.jdbc.PgStatement.executeInternal(PgStatement.java:496)
        at org.postgresql.jdbc.PgStatement.execute(PgStatement.java:413)
        - locked <0x000000060f0037b0> (a org.postgresql.jdbc.PgPreparedStatement)
        at org.postgresql.jdbc.PgPreparedStatement.executeWithFlags(PgPreparedStatement.java:190)
        - locked <0x000000060f0037b0> (a org.postgresql.jdbc.PgPreparedStatement)
        at org.postgresql.jdbc.PgPreparedStatement.executeQuery(PgPreparedStatement.java:134)
        - locked <0x000000060f0037b0> (a org.postgresql.jdbc.PgPreparedStatement)
        at java.lang.invoke.LambdaForm$DMH/0x0000000801251400.invokeInterface(java.base@20-ea/LambdaForm$DMH)
        at java.lang.invoke.LambdaForm$MH/0x0000000801377800.invoke(java.base@20-ea/LambdaForm$MH)
        at java.lang.invoke.Invokers$Holder.invokeExact_MT(java.base@20-ea/Invokers$Holder)
        at jdk.internal.reflect.DirectMethodHandleAccessor.invokeImpl(java.base@20-ea/DirectMethodHandleAccessor.java:154)
        at jdk.internal.reflect.DirectMethodHandleAccessor.invoke(java.base@20-ea/DirectMethodHandleAccessor.java:104)
        at java.lang.reflect.Method.invoke(java.base@20-ea/Method.java:578)
        at org.apache.tomcat.jdbc.pool.StatementFacade$StatementProxy.invoke(StatementFacade.java:114)
        at jdk.proxy2.$Proxy3.executeQuery(jdk.proxy2/Unknown Source)
        at datomic.sql$select.invokeStatic(sql.clj:80)
        at datomic.sql$select.invoke(sql.clj:75)
        at datomic.kv_sql.KVSql.get(kv_sql.clj:60)
        at datomic.kv_cluster.KVCluster$fn__9307$fn__9311$fn__9312.invoke(kv_cluster.clj:181)
        at datomic.kv_cluster$retry_fn$fn__9272.invoke(kv_cluster.clj:83)
        at datomic.kv_cluster$retry_fn.invokeStatic(kv_cluster.clj:83)
        at datomic.kv_cluster$retry_fn.invoke(kv_cluster.clj:58)
        at clojure.lang.AFn.applyToHelper(AFn.java:178)
        at clojure.lang.AFn.applyTo(AFn.java:144)
        at clojure.core$apply.invokeStatic(core.clj:673)
        at clojure.core$partial$fn__5914.doInvoke(core.clj:2660)
        at clojure.lang.RestFn.invoke(RestFn.java:421)
        at datomic.kv_cluster.KVCluster$fn__9307$fn__9311.invoke(kv_cluster.clj:180)
        at datomic.kv_cluster.KVCluster$fn__9307.invoke(kv_cluster.clj:178)
        at clojure.core$binding_conveyor_fn$fn__5823.invoke(core.clj:2047)
        at clojure.lang.AFn.call(AFn.java:18)
        at java.util.concurrent.FutureTask.run(java.base@20-ea/FutureTask.java:317)
        at java.util.concurrent.ThreadPoolExecutor.runWorker(java.base@20-ea/ThreadPoolExecutor.java:1144)
        at java.util.concurrent.ThreadPoolExecutor$Worker.run(java.base@20-ea/ThreadPoolExecutor.java:642)
        at java.lang.Thread.runWith(java.base@20-ea/Thread.java:1636)
        at java.lang.Thread.run(java.base@20-ea/Thread.java:1623)

"query-1" #70 [536783] daemon prio=5 os_prio=0 cpu=0.58ms elapsed=48.12s tid=0x00007f17725a2ec0 nid=536783 waiting on condition  [0x00007f1690df8000]
   java.lang.Thread.State: WAITING (parking)
        at jdk.internal.misc.Unsafe.park(java.base@20-ea/Native Method)
        - parking to wait for  <0x000000060f013e38> (a java.util.concurrent.FutureTask)
        at java.util.concurrent.locks.LockSupport.park(java.base@20-ea/LockSupport.java:221)
        at java.util.concurrent.FutureTask.awaitDone(java.base@20-ea/FutureTask.java:500)
        at java.util.concurrent.FutureTask.get(java.base@20-ea/FutureTask.java:190)
        at clojure.core$deref_future.invokeStatic(core.clj:2317)
        at clojure.core$future_call$reify__8544.deref(core.clj:7041)
        at clojure.core$deref.invokeStatic(core.clj:2337)
        at clojure.core$deref.invoke(core.clj:2323)
        at datomic.cluster$uncached_val_lookup$reify__1631.valAt(cluster.clj:192)
        at clojure.lang.RT.get(RT.java:791)
        at datomic.cache$double_lookup$reify__4537.valAt(cache.clj:225)
        at clojure.lang.RT.get(RT.java:791)
        at datomic.cache$lookup_transformer$reify__4522.valAt(cache.clj:93)
        at clojure.lang.RT.get(RT.java:791)
        at datomic.cache$lookup_with_inflight_cache$reify__4528$fn__4529.invoke(cache.clj:171)
        at clojure.lang.Delay.deref(Delay.java:42)
        - locked <0x000000060f0246e8> (a clojure.lang.Delay)
        at clojure.core$deref.invokeStatic(core.clj:2337)
        at clojure.core$deref.invoke(core.clj:2323)
        at datomic.cache$lookup_with_inflight_cache$reify__4528.valAt(cache.clj:169)
        at clojure.lang.RT.get(RT.java:791)
        at datomic.cache$lookup_cache$reify__4525.valAt(cache.clj:138)
        at clojure.lang.RT.get(RT.java:791)
        at datomic.common$getx.invokeStatic(common.clj:207)
        at datomic.common$getx.invoke(common.clj:203)
        at datomic.index.Index.seek(index.clj:561)
        at datomic.btset$seek.invokeStatic(btset.clj:399)
        at datomic.btset$seek.invoke(btset.clj:394)
        at datomic.db.Db.seekAEVT(db.clj:2368)
        at datomic.datalog$fn__6335$fn__6398.invoke(datalog.clj:482)
        at datomic.datalog$fn__6335$join__6432.invoke(datalog.clj:591)
        at datomic.datalog$fn__6335$fn__6435.invoke(datalog.clj:608)
        at datomic.common$pooled_mapv$fn__467$fn__468.invoke(common.clj:688)
        at clojure.lang.AFn.applyToHelper(AFn.java:152)
        at clojure.lang.AFn.applyTo(AFn.java:144)
        at clojure.core$apply.invokeStatic(core.clj:667)
        at clojure.core$with_bindings_STAR_.invokeStatic(core.clj:1990)
        at clojure.core$with_bindings_STAR_.doInvoke(core.clj:1990)
        at clojure.lang.RestFn.invoke(RestFn.java:425)
        at clojure.lang.AFn.applyToHelper(AFn.java:156)
        at clojure.lang.RestFn.applyTo(RestFn.java:132)
        at clojure.core$apply.invokeStatic(core.clj:671)
        at clojure.core$bound_fn_STAR_$fn__5818.doInvoke(core.clj:2020)
        at clojure.lang.RestFn.invoke(RestFn.java:397)
        at clojure.lang.AFn.call(AFn.java:18)
        at java.util.concurrent.FutureTask.run(java.base@20-ea/FutureTask.java:317)
        at java.util.concurrent.ThreadPoolExecutor.runWorker(java.base@20-ea/ThreadPoolExecutor.java:1144)
        at java.util.concurrent.ThreadPoolExecutor$Worker.run(java.base@20-ea/ThreadPoolExecutor.java:642)
        at java.lang.Thread.runWith(java.base@20-ea/Thread.java:1636)
        at java.lang.Thread.run(java.base@20-ea/Thread.java:1623)
```

Thus, a single dropped connection causes two, maybe three, stale threads.

## Case 3: a bricked application?


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
