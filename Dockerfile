# =============================================================================
# 多阶段构建：编译镜像和运行镜像分开
#
# ★ 为什么必须多阶段 ★
# 一个完整 JDK + Maven 的镜像约 500MB，而运行时只需要 JRE（约 180MB），
# 而且编译工具链留在生产镜像里 = 攻击面白白扩大一圈。
# 多阶段构建让最终镜像【只包含 jar 和 JRE】，构建缓存仍然保留在 build 阶段。
# =============================================================================

# ---------- 阶段 1：构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# ★ 先只拷 pom，再下依赖 ★
# Docker 的层缓存按"文件内容有没有变"判定。把 pom.xml 单独放一层，
# 改 Java 代码时这一层缓存命中，不会每次都重新下载几百 MB 依赖。
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:17-jre

# ★ 不用 root 跑应用 ★
# 容器逃逸类漏洞的第一道减速带就是"进程本身没有 root 权限"。
# 注意这里用 Debian/Ubuntu 系的 groupadd/useradd，不是 Alpine 的 addgroup/adduser ——
# 两者的参数完全不兼容，换了基础镜像这一行必须跟着换（否则构建期就报错，算是好事）。
RUN groupadd --system gateway && useradd --system --gid gateway --create-home gateway

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN chown -R gateway:gateway /app
USER gateway

EXPOSE 8081

# ★ ExitOnOutOfMemoryError：OOM 后继续活着只会变成"半个服务" ★
# 一个已经 OOM 的 JVM 会不断抛异常、不断拒绝请求，但探针可能还是绿的。
# 让它死掉，由编排层（compose/K8s）重启，比在这里苟延残喘更健康。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# ★ 健康检查打 actuator/health，不打 /ping ★
# /ping 只能证明"进程还在"，health 会聚合 Redis 等依赖的状态。
# 用 127.0.0.1 而不是 localhost：避免容器里 IPv6 解析差异导致的偶发失败。
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O - http://127.0.0.1:8081/actuator/health | grep -q '"status":"UP"' || exit 1

# exec：让 java 成为 PID 1 的直接子进程，能正确收到 SIGTERM 并优雅停机。
# 不 exec 的话 PID 1 是 sh，信号不会转发给 java —— 每次发布都要等超时强杀。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
