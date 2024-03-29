package com.github.ivarref;

import java.io.FileDescriptor;
import java.lang.foreign.*;
import java.lang.foreign.ValueLayout.OfByte;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketImpl;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

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

    private static String tcpi_state_str(int val) {
        if (val == TCP_LISTEN()) {
            return "LISTEN";
        } else if (val == TCP_CLOSE()) {
            return "CLOSE";
        } else if (val == TCP_CLOSE_WAIT()) {
            return "CLOSE_WAIT";
        } else if (val == TCP_CLOSING()) {
            return "CLOSING";
        } else if (val == TCP_ESTABLISHED()) {
            return "ESTABLISHED";
        } else if (val == TCP_FIN_WAIT1()) {
            return "FIN_WAIT_1";
        } else if (val == TCP_FIN_WAIT2()) {
            return "FIN_WAIT_2";
        } else if (val == TCP_LAST_ACK()) {
            return "LAST_ACK";
        } else if (val == TCP_SYN_RECV()) {
            return "SYN_RECV";
        } else if (val == TCP_SYN_SENT()) {
            return "SYN_SENT";
        } else if (val == TCP_TIME_WAIT()) {
            return "TIME_WAIT";
        } else {
            return "UNKNOWN state:" + val;
        }
    }

    public static int SOL_TCP() {
        return (int) 6L;
    }

    /*
    // from /usr/include/netinet/tcp.h
struct tcp_info
{
    uint8_t       tcpi_state;
    uint8_t       tcpi_ca_state;
    uint8_t       tcpi_retransmits;
    uint8_t       tcpi_probes;
    uint8_t       tcpi_backoff;
    uint8_t       tcpi_options;
    uint8_t       tcpi_snd_wscale : 4, tcpi_rcv_wscale : 4;

    uint32_t      tcpi_rto;
    uint32_t      tcpi_ato;
    uint32_t      tcpi_snd_mss;
    uint32_t      tcpi_rcv_mss;

    uint32_t      tcpi_unacked;
    uint32_t      tcpi_sacked;
    uint32_t      tcpi_lost;
    uint32_t      tcpi_retrans;
    uint32_t      tcpi_fackets;

  // Times.
    uint32_t      tcpi_last_data_sent;
    uint32_t      tcpi_last_ack_sent;     // Not remembered, sorry.
    uint32_t      tcpi_last_data_recv;
    uint32_t      tcpi_last_ack_recv;

    // Metrics.
    uint32_t      tcpi_pmtu;
    uint32_t      tcpi_rcv_ssthresh;
    uint32_t      tcpi_rtt;
    uint32_t      tcpi_rttvar;
    uint32_t      tcpi_snd_ssthresh;
    uint32_t      tcpi_snd_cwnd;
    uint32_t      tcpi_advmss;
    uint32_t      tcpi_reordering;

    uint32_t      tcpi_rcv_rtt;
    uint32_t      tcpi_rcv_space;

    uint32_t      tcpi_total_retrans;
};
     */
    public static final OfByte C_CHAR = ValueLayout.JAVA_BYTE;
    public static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT;
    public static final AddressLayout C_POINTER = ValueLayout.ADDRESS
            .withTargetLayout(MemoryLayout.sequenceLayout(java.lang.Long.MAX_VALUE, JAVA_BYTE));

    private static final GroupLayout tcpInfoStruct = MemoryLayout.structLayout(
            C_CHAR.withName("tcpi_state"),
            C_CHAR.withName("tcpi_ca_state"),
            C_CHAR.withName("tcpi_retransmits"),
            C_CHAR.withName("tcpi_probes"),
            C_CHAR.withName("tcpi_backoff"),
            C_CHAR.withName("tcpi_options"),
            MemoryLayout.paddingLayout(2),
            C_INT.withName("tcpi_rto"),
            C_INT.withName("tcpi_ato"),
            C_INT.withName("tcpi_snd_mss"),
            C_INT.withName("tcpi_rcv_mss"),
            C_INT.withName("tcpi_unacked"),
            C_INT.withName("tcpi_sacked"),
            C_INT.withName("tcpi_lost"),
            C_INT.withName("tcpi_retrans"),
            C_INT.withName("tcpi_fackets"),
            C_INT.withName("tcpi_last_data_sent"),
            C_INT.withName("tcpi_last_ack_sent"),
            C_INT.withName("tcpi_last_data_recv"),
            C_INT.withName("tcpi_last_ack_recv"),
            C_INT.withName("tcpi_pmtu"),
            C_INT.withName("tcpi_rcv_ssthresh"),
            C_INT.withName("tcpi_rtt"),
            C_INT.withName("tcpi_rttvar"),
            C_INT.withName("tcpi_snd_ssthresh"),
            C_INT.withName("tcpi_snd_cwnd"),
            C_INT.withName("tcpi_advmss"),
            C_INT.withName("tcpi_reordering"),
            C_INT.withName("tcpi_rcv_rtt"),
            C_INT.withName("tcpi_rcv_space"),
            C_INT.withName("tcpi_total_retrans")
    ).withName("tcp_info");

    private final static MethodHandle getsockopt() {
        final Linker linker = Linker.nativeLinker();
        final SymbolLookup stdlib = linker.defaultLookup();
        return linker.downcallHandle(stdlib.find("getsockopt").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        C_INT, // sockfd
                        C_INT, // level
                        C_INT, // optname
                        C_POINTER, // optval
                        C_POINTER  /* optlen */));
    }

    public static int socketImplToFd(SocketImpl sock) throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException, InvocationTargetException {
        Method getFileDescriptor = SocketImpl.class.getDeclaredMethod("getFileDescriptor");
        getFileDescriptor.setAccessible(true);
        FileDescriptor fd = (FileDescriptor) getFileDescriptor.invoke(sock);
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

    public static int socketToFd(Socket sock) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, NoSuchFieldException {
        Method m = Socket.class.getDeclaredMethod("getImpl");
        m.setAccessible(true);
        SocketImpl socket = (SocketImpl) m.invoke(sock);
        return socketImplToFd(socket);
    }

    public static int getFd(Object sock) throws Throwable {
        if (sock instanceof ServerSocket) {
            return serverSocketToFd((ServerSocket) sock);
        } else if (sock instanceof Socket) {
            return socketToFd((Socket) sock);
        } else {
            throw new RuntimeException("Unsupported type: " + sock.getClass());
        }
    }

    public static Map<String, Object> getTcpInfo(Object sock) throws Throwable {
        return tcpInfo(getFd(sock));
    }

    public static Map<String, Object> tcpInfo(int fd) throws Throwable {
        Map<String, Object> res = new TreeMap<>();
        try (Arena offHeap = Arena.ofConfined()) {
            MemorySegment tcpInfo = offHeap.allocate(tcpInfoStruct);
            MemorySegment lenPointer = offHeap.allocate(ValueLayout.JAVA_INT, 1);
            lenPointer.setAtIndex(ValueLayout.JAVA_INT, 0, (int) tcpInfo.byteSize());
            MethodHandle methodHandle = getsockopt();
            Object retValObj = methodHandle.invoke(fd, SOL_TCP(), TCP_INFO(), tcpInfo, lenPointer);
            int retval = (int) retValObj;
            if (retval != 0) {
//                System.err.println("getsockopt error: " + retval);
                throw new RuntimeException("getsockopt error: " + retval);
            } else {
                int bufLen = lenPointer.getAtIndex(C_INT, 0);
                if (bufLen != tcpInfo.byteSize()) {
                    System.err.println("New bufLen: " + bufLen + " vs original: " + tcpInfo.byteSize());
                }
                extractMembers(res, tcpInfo);
            }
        }
        return res;
    }

    private static final OfByte tcpi_state$LAYOUT = (OfByte)tcpInfoStruct.select(groupElement("tcpi_state"));
    private static final long tcpi_state$OFFSET = 0;
    public static byte tcpi_state(MemorySegment struct) {
        return struct.get(tcpi_state$LAYOUT, tcpi_state$OFFSET);
    }

    private static void extractMembers(Map<String, Object> res, MemorySegment tcpInfo) {
        for (MemoryLayout fld : tcpInfoStruct.memberLayouts()) {
            if (fld.name().isPresent()) {
                String fldName = fld.name().get();
                VarHandle varHandle = tcpInfoStruct.varHandle(groupElement(fldName));
                Object val = varHandle.get(tcpInfo, 0);
                res.put(fldName, val);
            }
        }
        res.put("tcpi_state_str", tcpi_state_str(tcpi_state(tcpInfo)));
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
