package com.github.ivarref;

import java.io.FileDescriptor;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketImpl;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

public class GetSockOpt {

    public static int TCP_INFO() {
        return (int) 11L;
    }

    public static int TCP_ESTABLISHED() {
        return (int) 1L;
    }

    public static int TCP_SYN_SENT() {
        return (int) 2L;
    }

    public static int TCP_SYN_RECV() {
        return (int) 3L;
    }

    public static int TCP_FIN_WAIT1() {
        return (int) 4L;
    }

    public static int TCP_FIN_WAIT2() {
        return (int) 5L;
    }

    public static int TCP_TIME_WAIT() {
        return (int) 6L;
    }

    public static int TCP_CLOSE() {
        return (int) 7L;
    }

    public static int TCP_CLOSE_WAIT() {
        return (int) 8L;
    }

    public static int TCP_LAST_ACK() {
        return (int) 9L;
    }

    public static int TCP_LISTEN() {
        return (int) 10L;
    }

    public static int TCP_CLOSING() {
        return (int) 11L;
    }

    public static int SOL_TCP() {
        return (int) 6L;
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

//    public static final VarHandle tcpiState = tcpInfoStruct.varHandle(groupElement("tcpi_state"));
//    public static final VarHandle tcpiLastDataRecv = tcpInfoStruct.varHandle(groupElement("tcpi_last_data_recv"));
//    public static final VarHandle tcpiUnacked = tcpInfoStruct.varHandle(groupElement("tcpi_unacked"));

    private final static Linker linker = Linker.nativeLinker();
    private final static SymbolLookup stdlib = linker.defaultLookup();

    private final static MethodHandle getsockopt = linker.downcallHandle(stdlib.find("getsockopt").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, // sockfd
                    ValueLayout.JAVA_INT, // level
                    ValueLayout.JAVA_INT, // optname
                    ValueLayout.ADDRESS, // optval
                    ValueLayout.ADDRESS  /* optlen */));

    public static int socketImplToFd(SocketImpl sock) throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException, InvocationTargetException {
        Method getFileDescriptor = SocketImpl.class.getDeclaredMethod("getFileDescriptor");
        getFileDescriptor.setAccessible(true);
        FileDescriptor fd = (FileDescriptor) getFileDescriptor.invoke(sock);
//        System.out.println("fd is: " + fd + " of type " + fd.getClass());

        Field fdField = FileDescriptor.class.getDeclaredField("fd");
        fdField.setAccessible(true);
        int fdInt = (int) fdField.get(fd);
        return fdInt;
    }

    public static int serverSocketToFd(ServerSocket sock) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, NoSuchFieldException {
        Method m = ServerSocket.class.getDeclaredMethod("getImpl");
        m.setAccessible(true);
        SocketImpl socket = (SocketImpl) m.invoke(sock);
        return socketImplToFd(socket);
    }

    public static Map<String, Object> tcpInfo(int fd) throws Throwable {
        Map<String, Object> res = new TreeMap<>();
        try (Arena offHeap = Arena.openConfined()) {
            MemorySegment tcpInfo = offHeap.allocate(tcpInfoStruct);
            MemorySegment lenPointer = offHeap.allocateArray(ValueLayout.JAVA_INT, 1);
            lenPointer.setAtIndex(ValueLayout.JAVA_INT, 0, (int) tcpInfo.byteSize());
            int retval = (int) getsockopt.invoke(fd, SOL_TCP(), TCP_INFO(), tcpInfo, lenPointer);
            if (retval != 0) {
                System.err.println("getsockopt error: " + retval);
            } else {
                int bufLen = lenPointer.getAtIndex(ValueLayout.JAVA_INT, 0);
                if (bufLen != tcpInfo.byteSize()) {
                    System.err.println("New bufLen: " + bufLen + " vs original: " + tcpInfo.byteSize());
                } else {
//                        System.out.println("Same buffer size");
                }
                List<MemoryLayout> memoryLayouts = tcpInfoStruct.memberLayouts();
                extractMembers(tcpInfoStruct, res, tcpInfo, memoryLayouts);
            }
        }
        return res;
    }

    private static void extractMembers(GroupLayout root, Map<String, Object> res, MemorySegment tcpInfo, List<MemoryLayout> memoryLayouts) {
        for (MemoryLayout layout : memoryLayouts) {
            if (layout.name().isPresent()) {
                String name = layout.name().get();
                System.out.println(name);
                VarHandle varHandle = root.varHandle(groupElement(name));
                Object val = varHandle.get(tcpInfo);
                res.put(name, val);
                if ("tcpi_state".equalsIgnoreCase(name)) {
                }
            } else if (layout instanceof GroupLayout) {
                // TOOD implement
//                GroupLayout group = (GroupLayout) layout;
//                VarHandle handle = layout.varHandle();
//                extractMembers(group, res, tcpInfo, group.memberLayouts());
//                System.out.println("wooho");
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = server.getLocalPort();
            System.out.println("Port is: " + port);
            System.out.println(tcpInfo(serverSocketToFd(server)));
        }
    }
}
