package com.example.sandbox.aio.browser.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AIO 当前浏览器实例的连接和视口信息。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BrowserInfo {

    /** 浏览器 User-Agent。 */
    @JsonProperty("user_agent")
    private String userAgent;

    /** Chrome DevTools Protocol 连接地址。 */
    @JsonProperty("cdp_url")
    private String cdpUrl;

    /** 浏览器 VNC 查看地址。 */
    @JsonProperty("vnc_url")
    private String vncUrl;

    /** 浏览器视口尺寸。 */
    private Viewport viewport;

    /** @return 浏览器 User-Agent */
    public String getUserAgent() {
        return userAgent;
    }

    /** @param userAgent 浏览器 User-Agent */
    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    /** @return CDP 连接地址 */
    public String getCdpUrl() {
        return cdpUrl;
    }

    /** @param cdpUrl CDP 连接地址 */
    public void setCdpUrl(String cdpUrl) {
        this.cdpUrl = cdpUrl;
    }

    /** @return VNC 查看地址 */
    public String getVncUrl() {
        return vncUrl;
    }

    /** @param vncUrl VNC 查看地址 */
    public void setVncUrl(String vncUrl) {
        this.vncUrl = vncUrl;
    }

    /** @return 浏览器视口尺寸 */
    public Viewport getViewport() {
        return viewport;
    }

    /** @param viewport 浏览器视口尺寸 */
    public void setViewport(Viewport viewport) {
        this.viewport = viewport;
    }

    /**
     * 浏览器视口尺寸。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Viewport {

        /** 视口宽度。 */
        private int width;

        /** 视口高度。 */
        private int height;

        /** @return 视口宽度 */
        public int getWidth() {
            return width;
        }

        /** @param width 视口宽度 */
        public void setWidth(int width) {
            this.width = width;
        }

        /** @return 视口高度 */
        public int getHeight() {
            return height;
        }

        /** @param height 视口高度 */
        public void setHeight(int height) {
            this.height = height;
        }
    }
}
