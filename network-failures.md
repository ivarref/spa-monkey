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

We will be using [nftables](https://wiki.nftables.org/wiki-nftables/index.php/What_is_nftables%3F) to simulate network errors.

## Setup

Read this section if you want to reproduce the output of the commands.

All commands require that `./db-up.sh` is running.
Running `./db-up.sh` will start a Datomic and PostgreSQL instance locally.
The following environment variables needs to be set:

* `DATOMIC_HTTP_USERNAME`: Username used to fetch datomic on prem transactor zip file.
* `DATOMIC_HTTP_PASSWORD`: Password used to fetch datomic on prem transactor zip file.
* `DATOMIC_LICENSE_KEY`: Datomic license key.
* `POSTGRES_PASSWORD`: Password to be used for PostgreSQL.

You will also want to: be prepared to enter your root password,
add `/usr/bin/nft` to sudoers for your user or run `clojure` using `sudo -E`.

Running this code requires Java 20 or later as it uses [JEP 434: Foreign Function & Memory API](https://openjdk.org/jeps/434).

## Case 1: TCP retry saves the day

Running `sudo -E ./tcp-retry.sh` you will see:

```
0001 00:00:03 [INFO] main /proc/sys/net/ipv4/tcp_retries2 is 15
0002 00:00:03 [INFO] main Clear all packet filters ...
0003 00:00:03 [INFO] main Executing sudo nft -f accept.txt ...
0004 00:00:03 [INFO] main Executing sudo nft -f accept.txt ... OK!
0005 00:00:05 [INFO] main Starting query on blocked connection ...
0006 00:00:05 [DEBUG] CLI-agent-send-off-pool-3 datomic.kv-cluster {:event :kv-cluster/get-val, :val-key "63f626cd-c6ef-4649-9fbd-979acc8dcd45", :phase :begin, :pid 369854, :tid 60}
0007 00:00:05 [INFO] CLI-agent-send-off-pool-3 Dropping TCP packets for 127.0.0.1:59820->127.0.0.1:5432 fd 152
0008 00:00:05 [INFO] CLI-agent-send-off-pool-3 Executing sudo nft -f drop.txt ...
0009 00:00:05 [INFO] CLI-agent-send-off-pool-3 Executing sudo nft -f drop.txt ... OK!
0010 00:00:05 [INFO] socket-watcher Initial state for fd 152 {open? true, tcpi_advmss 65483, tcpi_ato 40000, tcpi_backoff 0, tcpi_ca_state 0, tcpi_fackets 0, tcpi_last_ack_recv 154, tcpi_last_ack_sent 0, tcpi_last_data_recv 154, tcpi_last_data_sent 7, tcpi_lost 0, tcpi_options 7, tcpi_pmtu 65535, tcpi_probes 0, tcpi_rcv_mss 577, tcpi_rcv_rtt 1000, tcpi_rcv_space 65495, tcpi_rcv_ssthresh 65495, tcpi_reordering 3, tcpi_retrans 0, tcpi_retransmits 0, tcpi_rto 203333, tcpi_rtt 193, tcpi_rttvar 81, tcpi_sacked 0, tcpi_snd_cwnd 10, tcpi_snd_mss 32768, tcpi_snd_ssthresh 2147483647, tcpi_state 1, tcpi_state_str ESTABLISHED, tcpi_total_retrans 0, tcpi_unacked 0}
0011 00:00:06 [INFO] socket-watcher fd 152 tcpi_backoff 0 => 1 (In 190 ms)
0012 00:00:06 [INFO] socket-watcher fd 152 tcpi_backoff 1 => 2 (In 415 ms)
0013 00:00:07 [INFO] socket-watcher fd 152 tcpi_backoff 2 => 3 (In 830 ms)
0014 00:00:09 [INFO] socket-watcher fd 152 tcpi_backoff 3 => 4 (In 1653 ms)
0015 00:00:12 [INFO] socket-watcher fd 152 tcpi_backoff 4 => 5 (In 3412 ms)
0016 00:00:19 [INFO] socket-watcher fd 152 tcpi_backoff 5 => 6 (In 6610 ms)
0017 00:00:32 [INFO] socket-watcher fd 152 tcpi_backoff 6 => 7 (In 13231 ms)
0018 00:00:59 [INFO] socket-watcher fd 152 tcpi_backoff 7 => 8 (In 26667 ms)
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
After this we simply wait:
```
0010 00:01:00 [INFO] CLI-agent-send-off-pool-1 Tick
...
0014 00:02:00 [INFO] CLI-agent-send-off-pool-1 Tack
...
0066 00:15:00 [INFO] CLI-agent-send-off-pool-1 Tick
...
```

and then finally:

```
0070 00:15:54 [WARN] CLI-agent-send-off-pool-3 org.apache.tomcat.jdbc.pool.PooledConnection Unable to clear Warnings, connection will be closed.
0071 00:15:54 [INFO] CLI-agent-send-off-pool-3 datomic.kv-cluster {:event :kv-cluster/retry, :StorageGetBackoffMsec 0, :attempts 0, :max-retries 9, :cause "java.net.SocketException", :pid 10344, :tid 45}
0072 00:15:54 [INFO] CLI-agent-send-off-pool-3 Not dropping anything for 127.0.0.1:35970->127.0.0.1:5432
...
0080 00:15:54 [INFO] main Query on blocked connection ... Done in 00:15:48 aka 948623 milliseconds
0081 00:15:54 [INFO] main Waiting 90 seconds for datomic.process-monitor
0082 00:16:05 [INFO] Datomic Metrics Reporter datomic.process-monitor {:tid 37, :ObjectCacheCount 23, :AvailableMB 7850.0, :StorageGetMsec {:lo 1, :hi 949000, :sum 949003, :count 3}, :pid 10344, :event :metrics, :ObjectCache {:lo 0, :hi 0, :sum 0, :count 2}, :MetricsReport {:lo 1, :hi 1, :sum 1, :count 1}, :StorageGetBytes {:lo 65, :hi 2114, :sum 2269, :count 3}, :StorageGetBackoffMsec {:lo 0, :hi 0, :sum 0, :count 1}}
```

After approximately 16 minutes the kernel gives up
trying to re-send our packets and waiting for the corresponding
TCP acknowledgements. The kernel then closes the connection.

It's possible to verify 
that this is indeed what is
happening by changing the kernel
TCP retry number:
`sudo bash -c 'echo 6 > /proc/sys/net/ipv4/tcp_retries2'`
If you then re-run `./tcp-retry.sh` you will see
a much shorter timeout.

The default value of `/proc/sys/net/ipv4/tcp_retries2` is 15, i.e.
an unacknowledged packet is re-sent 15 times
before the connection is considered broken
and then closed by the kernel.
From the [kernel ip-sysctl documentation](https://www.kernel.org/doc/Documentation/networking/ip-sysctl.txt):

> The default value of 15 yields a hypothetical timeout of 924.6 seconds and is a lower bound for the effective timeout.  TCP will effectively time out at the first RTO which exceeds the hypothetical timeout.

In our case the timeout took ~940 seconds.

[//]: # (If some usually fast task takes just over ~940 seconds, or perhaps even a multiple of that, you may be having this issue.)

After the connection is closed by the kernel,
Datomic finally retries fetching the data on line 71.

There are a few more things to note here.
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
It does however report (line 82) that `:StorageGetMsec` had a `:hi[gh]`
of `949000`, i.e. around 16 minutes. 
This is logged at an INFO-level, making it rather hard
to spot.

## Case 2: a query that hangs forever?

In case 1 we saw what happened when the TCP send buffer had unacknowledged data on a dropped connection: the kernel saved us and the Datomic query, albeit taking ~16 minutes.

What happens if the connection becomes blocked after the send buffer is acknowledged,
but before a response is received?

[//]: # (This is a slightly more convoluted scenario to reproduce.)

We will introduce an in-process TCP proxy that forwards packets to and from the database.
This allows for dropping the connection to the peer
on the receival of data from the database.
At this point in time we want to be sure that
the send buffer is ACK-ed.

[//]: # (In this specific case, I'd say you would find that your tcp_info structure would hold a nonzero tcp_info.tcpi_unacked. You'd get this via getsockopt(TCP_INFO).)

[//]: # (https://stackoverflow.com/questions/443134/in-linux-how-do-i-know-if-an-ack-is-received-to-a-certain-tcp-packet)

[//]: # (Use wireshark/tshark to verify/see that ACK *has* arrived... ?! What is an ACK? How fast is/was the ACK?)

Let find out by executing `clojure -X:case2`:

### Analysis

On the surface it appears that a read attempt times out after 16 minutes, and
that after 3 consecutive attempts an exception is thrown.

What actually happened was that the TCP packets were dropped, and thus
the TCP send buffer had unacknowledged data. After
retrying a number of times
and the data still not acknowledged, the connection was closed by the kernel.

There are a few more things to note:

* Only a single warning is logged before the final timeout.
This is logged by PooledConnection of tomcat-jdbc.

* For 48 minutes `process-monitor` reports that everything is fine.
After 49 minutes `process-monitor` finally logs that `StorageGetMsec` took `2900000`
milliseconds or 48.3 minutes. This is logged at the `INFO`-level.

* The last message from `process-monitor` seems to indicate that
`:StorageGetMsec` succeeded, albeit taking a very long time.
It did in fact not succeed.

* You will want to monitor `:StorageGetMsec/hi` to see if there are network anomalies.

## Case 2: a connection becomes blocked by a firewall after the TCP send buffer is acknowledged

In case 1 we saw what happened when the TCP send buffer had unacknowledged data.

What happens if the connection becomes blocked after the send buffer is acknowledged,
but before a response is received? Let find out by executing `clojure -X:case2`:

```
00:00:06 INFO case-2 Starting read-segment on single blocked connection
00:00:06 DEBUG kv-cluster {:event :kv-cluster/get-val, :val-key "854f8149-7116-45dc-b3df-5b57a5cd1e4e", :phase :begin, :pid 199703, :tid 39}
00:00:06 WARN spa-monkey Start dropping bytes. Id: #uuid "2e23ee78-6d08-43d8-9846-74efb9f0a80c"
...
00:02:06 INFO case-2 Still waiting for read segment
...
00:56:06 INFO case-2 Still waiting for read segment
...
13:37:05 INFO process-monitor {:MetricsReport {:lo 1, :hi 1, :sum 1, :count 1}, :AvailableMB 7630.0, :ObjectCacheCount 20, :event :metrics, :pid 199703, :tid 29}
13:37:07 INFO case-2 Still waiting for read segment
```

After 13 hours, Datomic is still patiently waiting for a response.

We can nREPL into `localhost:7777` to verify that only a single connection
is blocked:

```clojure
(let [conn @conn-atom
      start-time (System/currentTimeMillis)
      _segment (u/read-segment conn "854f8149-aaaa-45dc-b3df-5b57a5cd1e4e")]
  (log/info "Got segment after" (- (System/currentTimeMillis) start-time) "milliseconds"))
13:39:05 DEBUG kv-cluster {:event :kv-cluster/get-val, :val-key "854f8149-aaaa-45dc-b3df-5b57a5cd1e4e", :phase :begin, :pid 199703, :tid 45}
13:39:05 DEBUG kv-cluster {:event :kv-cluster/get-val, :val-key "854f8149-aaaa-45dc-b3df-5b57a5cd1e4e", :msec 46.8, :phase :end, :pid 199703, :tid 45}
13:39:05 INFO case-2 Got segment after 49 milliseconds
```

### Analysis

Datomic, or rather the PostgreSQL driver, is content with waiting forever
for a reply. This may happen if the connection is blocked by a firewall
(or the remote server goes down without properly closing the TCP connection)
and the TCP send buffer is acknowledged.

There is nothing in the logs indicating that two threads have been waiting for
over 13 hours.

Is it a good idea to wait without a timeout? It might be reasonable in some cases.
What's more important is that if you are *potentially* waiting forever,
you should at least be aware of it.
I am unable to find any indication of
this in the Datomic logs, particularly for case 2.

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
