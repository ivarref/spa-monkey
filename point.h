
#include <sys/socket.h>

#include <netinet/tcp.h>

struct Point2d {
    double x;
    double y;
};

double distance(struct Point2d);
