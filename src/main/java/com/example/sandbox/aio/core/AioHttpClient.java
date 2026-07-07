package com.example.sandbox.aio.core;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * AIO REST 基础传输客户端。
 *
 * <p>该类集中管理 WebClient、超时、JSON 信封、二进制响应和 multipart 上传，
 * 领域 API 不再重复处理 HTTP 细节。</p>
 */
public class AioHttpClient {

    /** AIO 普通请求的默认响应超时。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** 单次响应允许在内存中缓冲的最大字节数。 */
    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    /** 安静健康检查使用的 JDK HTTP 客户端，避免 Reactor Netty 在预期失败时刷 WARN 日志。 */
    private static final java.net.http.HttpClient QUIET_HEALTH_CLIENT = java.net.http.HttpClient.newBuilder()
            // 强制 HTTP/1.1：默认 HTTP/2 会在明文连接上发起 h2c 升级，沙箱 nginx 不支持，
            // 可能导致健康检查路径返回 502 而误判沙箱不健康。
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 当前用户 Sandbox 的 AIO 基础地址。 */
    private final String baseUrl;

    /** 用于发送 AIO HTTP 请求的 WebClient。 */
    private final WebClient webClient;

    /** 用于解析泛型响应信封和类型模型的 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * 根据动态 AIO endpoint 创建传输客户端。
     *
     * @param baseUrl 当前用户 Sandbox 的 AIO 基础地址
     */
    public AioHttpClient(String baseUrl) {
        this(baseUrl, createWebClient(baseUrl), new ObjectMapper());
    }

    /**
     * 使用已有 WebClient 创建传输客户端，主要用于组合和隔离测试。
     *
     * @param webClient   已配置基础地址的 WebClient
     * @param objectMapper JSON 映射器
     */
    public AioHttpClient(WebClient webClient, ObjectMapper objectMapper) {
        this(null, webClient, objectMapper);
    }

    /**
     * 使用已有 WebClient 和基础地址创建传输客户端。
     *
     * @param baseUrl      当前用户 Sandbox 的 AIO 基础地址；测试替身可为空
     * @param webClient    已配置基础地址的 WebClient
     * @param objectMapper JSON 映射器
     */
    public AioHttpClient(String baseUrl, WebClient webClient, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 对 JSON endpoint 发起 GET，并返回通用 Map 响应。
     *
     * @param path REST 路径
     * @return 完整响应对象
     */
    public Map<String, Object> getMap(String path) {
        return getMap(path, null);
    }

    /**
     * 对 JSON endpoint 发起 GET，并返回通用 Map 响应。
     *
     * @param path    REST 路径
     * @param timeout 单次调用超时；为空时使用传输层默认超时
     * @return 完整响应对象
     */
    public Map<String, Object> getMap(String path, Duration timeout) {
        var response = webClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                });
        if (timeout != null) {
            response = response.timeout(timeout);
        }
        return response.block();
    }

    /**
     * 对 JSON endpoint 发起 POST，并返回通用 Map 响应。
     *
     * @param path REST 路径
     * @param body 请求体
     * @return 完整响应对象
     */
    public Map<String, Object> postMap(String path, Object body) {
        return postMap(path, body, null);
    }

    /**
     * 对 JSON endpoint 发起 POST，并返回通用 Map 响应。
     *
     * @param path    REST 路径
     * @param body    请求体
     * @param timeout 单次调用超时；为空时使用传输层默认超时
     * @return 完整响应对象
     */
    public Map<String, Object> postMap(String path, Object body, Duration timeout) {
        var response = webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                });
        if (timeout != null) {
            response = response.timeout(timeout);
        }
        return response.block();
    }

    /**
     * 对 JSON endpoint 发起 POST，并返回服务端原始文本。
     *
     * <p>Shell 执行接口沿用旧客户端行为：先拿原始响应字符串，再由领域层解析。
     * 这样可以保留原始响应日志，也避免通用 Map 解析改变 shell/exec 的兼容语义。</p>
     *
     * @param path REST 路径
     * @param body 请求体
     * @return 服务端原始文本
     */
    public String postText(String path, Object body) {
        return webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /**
     * 对 JSON endpoint 发起 GET，并将响应信封中的 data 转换为指定类型。
     *
     * @param path     REST 路径
     * @param dataType data 字段类型
     * @param <T>      data 字段的 Java 类型
     * @return data；服务端返回空 data 时为 null
     */
    public <T> T getData(String path, Class<T> dataType) {
        Map<String, Object> response = getMap(path);
        return convertData(response, dataType);
    }

    /**
     * 对 JSON endpoint 发起 POST，并将响应信封中的 data 转换为指定类型。
     *
     * @param path     REST 路径
     * @param body     请求体
     * @param dataType data 字段类型
     * @param <T>      data 字段的 Java 类型
     * @return data；服务端返回空 data 时为 null
     */
    public <T> T postData(String path, Object body, Class<T> dataType) {
        Map<String, Object> response = postMap(path, body);
        return convertData(response, dataType);
    }

    /**
     * 获取二进制响应。
     *
     * @param path       含查询参数的 REST 路径
     * @param mediaType 期望的响应媒体类型
     * @return 响应字节；空响应时为 null
     */
    public byte[] getBytes(String path, MediaType mediaType) {
        return webClient.get()
                .uri(path)
                .accept(mediaType)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
    }

    /**
     * 获取二进制响应（使用 URI 模板变量，由 WebClient 负责编码）。
     *
     * @param pathTemplate URI 模板，如 "/v1/file/download?path={path}"
     * @param variables    模板变量
     * @param mediaType    期望的响应媒体类型
     * @return 响应字节；空响应时为 null
     */
    public byte[] getBytes(String pathTemplate, Map<String, String> variables, MediaType mediaType) {
        return webClient.get()
                .uri(pathTemplate, variables)
                .accept(mediaType)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
    }

    /**
     * 上传二进制文件到 AIO multipart endpoint。
     *
     * @param path       REST 路径
     * @param targetPath Sandbox 内目标路径
     * @param content    文件内容
     * @return 完整响应对象
     */
    public Map<String, Object> postMultipart(String path, String targetPath, byte[] content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                int slash = targetPath.lastIndexOf('/');
                return slash >= 0 ? targetPath.substring(slash + 1) : targetPath;
            }
        });
        builder.part("path", targetPath);

        return webClient.post()
                .uri(path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(builder.build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();
    }

    /**
     * 以独立超时执行轻量 GET 请求。
     *
     * @param path    REST 路径
     * @param timeout 单次调用超时
     * @return 服务端原始文本
     */
    public String getText(String path, Duration timeout) {
        return webClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .block();
    }

    /**
     * 以安静方式执行轻量 GET 请求。
     *
     * <p>该方法专供启动健康检查使用。AIO 刚启动时连接拒绝、连接过早关闭和超时是预期现象，
     * 使用 JDK HTTP 客户端可以把这些失败交给调用方判断，避免 Reactor Netty 在每次失败时输出 WARN。</p>
     *
     * @param path    REST 路径
     * @param timeout 单次调用超时
     * @return 服务端原始文本
     */
    public String getTextQuietly(String path, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return getText(path, timeout);
        }
        try {
            var request = java.net.http.HttpRequest.newBuilder(resolve(path))
                    .timeout(timeout)
                    .GET()
                    .build();
            var response = QUIET_HEALTH_CLIENT.send(
                    request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AioApiException("AIO 安静 GET 请求返回 HTTP " + response.statusCode());
            }
            return response.body();
        } catch (Exception e) {
            throw new AioApiException("AIO 安静 GET 请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据基础地址和 REST 路径构造完整 URI。
     *
     * @param path REST 路径
     * @return 完整 URI
     */
    private URI resolve(String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(normalizedBase + normalizedPath);
    }

    /**
     * 将响应信封中的 data 字段转换为类型模型。
     *
     * @param response 完整响应 Map
     * @param dataType 目标类型
     * @param <T>      目标 Java 类型
     * @return 转换后的 data
     * @throws AioApiException 当响应为空、失败或类型转换失败时抛出
     */
    private <T> T convertData(Map<String, Object> response, Class<T> dataType) {
        if (response == null) {
            throw new AioApiException("AIO API 返回空响应");
        }
        if (Boolean.FALSE.equals(response.get("success"))) {
            throw new AioApiException("AIO API 调用失败: " + response.get("message"));
        }
        Object data = response.get("data");
        if (data == null) {
            return null;
        }
        try {
            JavaType type = objectMapper.getTypeFactory().constructType(dataType);
            return objectMapper.convertValue(data, type);
        } catch (IllegalArgumentException e) {
            throw new AioApiException("AIO API 响应解析失败: " + dataType.getSimpleName(), e);
        }
    }

    /**
     * 创建项目统一配置的 WebClient。
     *
     * @param baseUrl AIO 基础地址
     * @return 可复用的 WebClient
     */
    private static WebClient createWebClient(String baseUrl) {
        HttpClient httpClient = HttpClient.create().responseTimeout(DEFAULT_TIMEOUT);
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
    }
}
