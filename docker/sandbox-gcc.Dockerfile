# 轻量 C/C++ 沙箱镜像：基于 Alpine（~8MB）+ gcc/g++ 编译工具
# 相比官方 gcc:13（展开 1.35GB）大幅缩小到 ~200MB
# 注意：Alpine 使用 musl libc（非 glibc），对标准 C/C++ 程序编译运行完全兼容
FROM alpine:3.19
RUN apk add --no-cache gcc g++ musl-dev make
WORKDIR /tmp
