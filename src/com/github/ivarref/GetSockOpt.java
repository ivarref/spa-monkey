package com.github.ivarref;

import java.io.FileDescriptor;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketImpl;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

public class GetSockOpt {

    public static int TCP_INFO() {
        return (int) 11L;
    }

    public static int SOL_TCP() {
        return (int)6L;
    }

    static final GroupLayout tcpInfoStruct = MemoryLayout.structLayout(
            ValueLayout.JAVA_BYTE.withName("tcpi_state"),
            ValueLayout.JAVA_BYTE.withName("tcpi_ca_state"),
            ValueLayout.JAVA_BYTE.withName("tcpi_retransmits"),
            ValueLayout.JAVA_BYTE.withName("tcpi_probes"),
            ValueLayout.JAVA_BYTE.withName("tcpi_backoff"),
            ValueLayout.JAVA_BYTE.withName("tcpi_options"),
            MemoryLayout.structLayout(
                    MemoryLayout.paddingLayout(4).withName("tcpi_snd_wscale"),
                    MemoryLayout.paddingLayout(4).withName("tcpi_rcv_wscale"),
                    MemoryLayout.paddingLayout(8)
            ),
            ValueLayout.JAVA_INT.withName("tcpi_rto"),
            ValueLayout.JAVA_INT.withName("tcpi_ato"),
            ValueLayout.JAVA_INT.withName("tcpi_snd_mss"),
            ValueLayout.JAVA_INT.withName("tcpi_rcv_mss"),
            ValueLayout.JAVA_INT.withName("tcpi_unacked"),
            ValueLayout.JAVA_INT.withName("tcpi_sacked"),
            ValueLayout.JAVA_INT.withName("tcpi_lost"),
            ValueLayout.JAVA_INT.withName("tcpi_retrans"),
            ValueLayout.JAVA_INT.withName("tcpi_fackets"),
            ValueLayout.JAVA_INT.withName("tcpi_last_data_sent"),
            ValueLayout.JAVA_INT.withName("tcpi_last_ack_sent"),
            ValueLayout.JAVA_INT.withName("tcpi_last_data_recv"),
            ValueLayout.JAVA_INT.withName("tcpi_last_ack_recv"),
            ValueLayout.JAVA_INT.withName("tcpi_pmtu"),
            ValueLayout.JAVA_INT.withName("tcpi_rcv_ssthresh"),
            ValueLayout.JAVA_INT.withName("tcpi_rtt"),
            ValueLayout.JAVA_INT.withName("tcpi_rttvar"),
            ValueLayout.JAVA_INT.withName("tcpi_snd_ssthresh"),
            ValueLayout.JAVA_INT.withName("tcpi_snd_cwnd"),
            ValueLayout.JAVA_INT.withName("tcpi_advmss"),
            ValueLayout.JAVA_INT.withName("tcpi_reordering"),
            ValueLayout.JAVA_INT.withName("tcpi_rcv_rtt"),
            ValueLayout.JAVA_INT.withName("tcpi_rcv_space"),
            ValueLayout.JAVA_INT.withName("tcpi_total_retrans")
    ).withName("tcp_info");

    public static void main(String[] args) throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup stdlib = linker.defaultLookup();

//        int getsockopt(int sockfd, int level, int optname,
//        void optval[restrict *.optlen],
//        socklen_t *restrict optlen);

        MethodHandle getsockopt = linker.downcallHandle(stdlib.find("getsockopt").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, // sockfd
                        ValueLayout.JAVA_INT, // level
                        ValueLayout.JAVA_INT, // optname
                        ValueLayout.ADDRESS, // optval
                        ValueLayout.ADDRESS  /* optlen */));

        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            Method m = ServerSocket.class.getDeclaredMethod("getImpl");
            m.setAccessible(true);
            SocketImpl socket = (SocketImpl) m.invoke(server);
            System.out.println("socketImpl is: " + socket + " of type " + socket.getClass());

            Method getFileDescriptor = SocketImpl.class.getDeclaredMethod("getFileDescriptor");
            getFileDescriptor.setAccessible(true);
            FileDescriptor fd = (FileDescriptor) getFileDescriptor.invoke(socket);
            System.out.println("fd is: " + fd + " of type " + fd.getClass());

            Field fdField = FileDescriptor.class.getDeclaredField("fd");
            fdField.setAccessible(true);
            int fdInt = (int) fdField.get(fd);
            System.out.println("fdInt is: " + fdInt);
            int port = server.getLocalPort();
            System.out.println("Port is: " + port);

            /*
       The socklen_t type
In the original BSD sockets implementation (and on other older systems) the third argument  of  accept()  was  declared  as  an
int *.  A POSIX.1g draft standard wanted to change it into a size_t *C; later POSIX standards and glibc 2.x have socklen_t * .
             */

            try (Arena offHeap = Arena.openConfined()) {
                MemorySegment tcpInfo = offHeap.allocate(tcpInfoStruct);
                MemorySegment lenPointer = offHeap.allocateArray(ValueLayout.JAVA_INT, 1);
                lenPointer.setAtIndex(ValueLayout.JAVA_INT, 0, (int)tcpInfo.byteSize());
                int retval = (int) getsockopt.invoke(fdInt, SOL_TCP(), TCP_INFO(), tcpInfo, lenPointer);
                if (retval != 0) {
                    System.out.println("getsockopt error: " + retval);
                } else {
                    System.out.println("getsockopt OK!");
                    int bufLen = lenPointer.getAtIndex(ValueLayout.JAVA_INT, 0);
                    if (bufLen != tcpInfo.byteSize()) {
                        System.out.println("New bufLen: " + bufLen + " vs original: " + tcpInfo.byteSize());
                    } else {
//                        System.out.println("Same buffer size");
                    }
                    VarHandle tcpiState = tcpInfoStruct.varHandle(groupElement("tcpi_state"));
                    VarHandle tcpiLastDataRecv = tcpInfoStruct.varHandle(groupElement("tcpi_last_data_recv"));
                    VarHandle tcpiUnacked = tcpInfoStruct.varHandle(groupElement("tcpi_unacked"));
                    System.out.println(tcpiState.get(tcpInfo));
                    System.out.println(tcpiLastDataRecv.get(tcpInfo));
                    System.out.println(tcpiUnacked.get(tcpInfo));
                }
            }

            //        int getsockopt(int sockfd, int level, int optname,
//        void optval[restrict *.optlen],
//        socklen_t *restrict optlen);
        }
    }
}
