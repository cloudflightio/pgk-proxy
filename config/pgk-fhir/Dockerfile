FROM busybox:stable-uclibc AS busybox

FROM hapiproject/hapi:v8.6.0-1
COPY --from=busybox /bin/wget /usr/local/bin/wget