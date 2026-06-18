import { chromium } from "playwright-core";

/** 沙箱内部 Chrome 版本信息接口。 */
const INTERNAL_CDP_VERSION_URL = "http://127.0.0.1:9222/json/version";

/**
 * 从沙箱内部 Chrome 调试端口读取真实的 CDP WebSocket 地址。
 *
 * @returns {Promise<string>} 沙箱内部可访问的 CDP WebSocket 地址
 * @throws {Error} 调试端口不可访问或响应缺少 WebSocket 地址时抛出
 */
export async function getInternalCdpUrl() {
  const response = await fetch(INTERNAL_CDP_VERSION_URL);
  if (!response.ok) {
    throw new Error(`获取内部 CDP 地址失败: HTTP ${response.status}`);
  }

  const browserInfo = await response.json();
  if (!browserInfo.webSocketDebuggerUrl) {
    throw new Error("内部 CDP 响应缺少 webSocketDebuggerUrl");
  }

  // Chrome 可能返回 localhost，强制使用 IPv4 可避免 Node 将其解析为未监听的 ::1。
  const cdpUrl = new URL(browserInfo.webSocketDebuggerUrl);
  cdpUrl.hostname = "127.0.0.1";
  return cdpUrl.toString();
}

/**
 * 连接 AIO 管理的 Chrome 实例并返回当前活动页面。
 *
 * @returns {Promise<{browser: import("playwright-core").Browser, page: import("playwright-core").Page}>}
 * @throws {Error} 内部 CDP 地址不可用、连接失败或浏览器中没有页面时抛出
 */
export async function connectActivePage() {
  const cdpUrl = await getInternalCdpUrl();
  const browser = await chromium.connectOverCDP(cdpUrl);
  const contexts = browser.contexts();
  const pages = contexts.flatMap((context) => context.pages());
  const page = pages.at(-1);
  if (!page) {
    await browser.close();
    throw new Error("AIO browser has no active page");
  }
  return { browser, page };
}

/**
 * 执行轻量级运行时和内部 CDP 连通性检查。
 *
 * @returns {Promise<{ok: true, url: string, title: string}>} 连通状态和当前页面信息
 * @throws {Error} 内部 CDP 地址不可用或浏览器连接失败时抛出
 */
export async function health() {
  const { browser, page } = await connectActivePage();
  try {
    return {
      ok: true,
      url: page.url(),
      title: await page.title()
    };
  } finally {
    await browser.close();
  }
}

/**
 * 返回当前活动页面的紧凑语义快照。
 *
 * @param {{maxElements?: number, maxTextChars?: number}} options 快照范围限制
 * @returns {Promise<object>} 页面标识、正文摘要、标题层级、语义区域、视口和交互元素
 * @throws {Error} 内部 CDP 地址不可用、浏览器连接失败或页面读取失败时抛出
 */
export async function inspect(options = {}) {
  const maxElements = Math.min(Math.max(Number(options.maxElements) || 80, 1), 200);
  const maxTextChars = Math.min(Math.max(Number(options.maxTextChars) || 8000, 1000), 20000);
  const { browser, page } = await connectActivePage();
  try {
    return await page.evaluate(({ elementLimit, textLimit }) => {
      const normalize = (value) => String(value || "").replace(/\s+/g, " ").trim();
      const compact = (value, limit = 300) => normalize(value).slice(0, limit);
      const visible = (element) => {
        const style = window.getComputedStyle(element);
        const rect = element.getBoundingClientRect();
        return style.visibility !== "hidden"
          && style.display !== "none"
          && Number(style.opacity) !== 0
          && rect.width > 0
          && rect.height > 0;
      };
      const referencedText = (element, attribute) => {
        const ids = normalize(element.getAttribute(attribute)).split(" ").filter(Boolean);
        return compact(ids
          .map((id) => document.getElementById(id)?.innerText
            || document.getElementById(id)?.textContent)
          .filter(Boolean)
          .join(" "));
      };
      const associatedLabel = (element) => {
        if (element.labels?.length) {
          return compact([...element.labels]
            .map((label) => label.innerText || label.textContent)
            .join(" "));
        }
        return referencedText(element, "aria-labelledby");
      };
      const inferredRole = (element) => {
        const explicitRole = normalize(element.getAttribute("role"));
        if (explicitRole) {
          return explicitRole;
        }
        const tag = element.tagName.toLowerCase();
        const type = normalize(element.getAttribute("type")).toLowerCase();
        if (tag === "a" && element.hasAttribute("href")) return "link";
        if (tag === "button" || tag === "summary") return "button";
        if (tag === "textarea") return "textbox";
        if (tag === "select") return element.multiple ? "listbox" : "combobox";
        if (element.isContentEditable) return "textbox";
        if (element.hasAttribute("onclick")) return "button";
        if (tag !== "input") return "";
        if (["button", "submit", "reset", "image"].includes(type)) return "button";
        if (type === "checkbox") return "checkbox";
        if (type === "radio") return "radio";
        if (type === "range") return "slider";
        if (type === "number") return "spinbutton";
        if (type === "search") return "searchbox";
        if (type === "hidden") return "";
        return "textbox";
      };
      const accessibleName = (element) => {
        const labelledBy = referencedText(element, "aria-labelledby");
        const ariaLabel = compact(element.getAttribute("aria-label"));
        const label = associatedLabel(element);
        const alt = compact(element.getAttribute("alt"));
        const title = compact(element.getAttribute("title"));
        const text = compact(element.innerText || element.textContent);
        const placeholder = compact(element.getAttribute("placeholder"));
        const value = compact(element.value);
        return labelledBy || ariaLabel || label || alt || title || text || placeholder || value;
      };
      const selectorFor = (element) => {
        if (element.id) {
          return `#${CSS.escape(element.id)}`;
        }
        const testId = element.getAttribute("data-testid");
        if (testId) {
          return `[data-testid="${CSS.escape(testId)}"]`;
        }
        const name = element.getAttribute("name");
        if (name) {
          return `${element.tagName.toLowerCase()}[name="${CSS.escape(name)}"]`;
        }
        const parts = [];
        let current = element;
        while (current && current.nodeType === Node.ELEMENT_NODE && parts.length < 5) {
          let part = current.tagName.toLowerCase();
          const siblings = current.parentElement
            ? [...current.parentElement.children].filter((item) => item.tagName === current.tagName)
            : [];
          if (siblings.length > 1) {
            part += `:nth-of-type(${siblings.indexOf(current) + 1})`;
          }
          parts.unshift(part);
          current = current.parentElement;
        }
        return parts.join(" > ");
      };

      const allCandidates = [...document.querySelectorAll(
        "a[href],button,input,textarea,select,[role='button'],[role='link'],"
          + "[role='checkbox'],[role='radio'],[role='combobox'],[contenteditable='true'],"
          + "summary,[onclick],[tabindex]:not([tabindex='-1'])"
      )].filter(visible);
      const candidates = allCandidates.slice(0, elementLimit);

      const elements = candidates.map((element, index) => {
        const type = element.getAttribute("type") || "";
        const password = type.toLowerCase() === "password";
        const rect = element.getBoundingClientRect();
        return {
          ref: `e${index + 1}`,
          tag: element.tagName.toLowerCase(),
          role: inferredRole(element),
          accessibleName: accessibleName(element),
          label: associatedLabel(element),
          description: referencedText(element, "aria-describedby"),
          type,
          text: compact(element.innerText || element.textContent),
          ariaLabel: compact(element.getAttribute("aria-label")),
          placeholder: compact(element.getAttribute("placeholder")),
          title: compact(element.getAttribute("title")),
          testId: element.getAttribute("data-testid") || "",
          name: element.getAttribute("name") || "",
          value: password ? "[password]" : compact(element.value),
          href: element.href || "",
          disabled: Boolean(element.disabled) || element.getAttribute("aria-disabled") === "true",
          required: Boolean(element.required) || element.getAttribute("aria-required") === "true",
          readOnly: Boolean(element.readOnly) || element.getAttribute("aria-readonly") === "true",
          focused: element === document.activeElement,
          checked: typeof element.checked === "boolean" ? element.checked : undefined,
          selectedOptions: element.tagName === "SELECT"
            ? [...element.selectedOptions].map((option) => ({
                text: compact(option.textContent, 150),
                value: option.value
              }))
            : undefined,
          options: element.tagName === "SELECT"
            ? [...element.options].slice(0, 20).map((option) => ({
                text: compact(option.textContent, 150),
                value: option.value,
                selected: option.selected,
                disabled: option.disabled
              }))
            : undefined,
          optionsTruncated: element.tagName === "SELECT" && element.options.length > 20
            ? true
            : undefined,
          selector: selectorFor(element),
          box: {
            x: Math.round(rect.x),
            y: Math.round(rect.y),
            width: Math.round(rect.width),
            height: Math.round(rect.height)
          }
        };
      });

      const headings = [...document.querySelectorAll("h1,h2,h3,h4,h5,h6")]
        .filter(visible)
        .slice(0, 30)
        .map((element) => ({
          level: Number(element.tagName.slice(1)),
          text: compact(element.innerText || element.textContent)
        }));
      const landmarkSelector = "header,nav,main,aside,footer,form,[role='banner'],"
        + "[role='navigation'],[role='main'],[role='complementary'],"
        + "[role='contentinfo'],[role='search'],[role='form'],[role='region']";
      const landmarkRole = (element) => {
        const explicitRole = normalize(element.getAttribute("role"));
        if (explicitRole) return explicitRole;
        return {
          header: "banner",
          nav: "navigation",
          main: "main",
          aside: "complementary",
          footer: "contentinfo",
          form: "form"
        }[element.tagName.toLowerCase()] || element.tagName.toLowerCase();
      };
      const landmarkName = (element) => {
        const labelledBy = referencedText(element, "aria-labelledby");
        const ariaLabel = compact(element.getAttribute("aria-label"));
        const title = compact(element.getAttribute("title"));
        const heading = element.querySelector("h1,h2,h3,h4,h5,h6");
        return labelledBy || ariaLabel || title
          || compact(heading?.innerText || heading?.textContent);
      };
      const allHeadings = [...document.querySelectorAll("h1,h2,h3,h4,h5,h6")];
      const allLandmarks = [...document.querySelectorAll(landmarkSelector)];
      const landmarks = allLandmarks
        .filter(visible)
        .slice(0, 20)
        .map((element) => ({
          role: landmarkRole(element),
          name: landmarkName(element)
        }));
      const mainContent = [...document.querySelectorAll("main,[role='main']")].find(visible);
      const textSource = mainContent || document.body;
      const bodyText = normalize(textSource?.innerText);

      return {
        url: location.href,
        title: document.title,
        viewport: {
          width: window.innerWidth,
          height: window.innerHeight
        },
        headings,
        landmarks,
        textScope: mainContent ? "main" : "body",
        text: bodyText.slice(0, textLimit),
        elements,
        truncated: {
          text: bodyText.length > textLimit,
          elements: allCandidates.length > elementLimit,
          headings: allHeadings.filter(visible).length > 30,
          landmarks: allLandmarks.filter(visible).length > 20
        }
      };
    }, { elementLimit: maxElements, textLimit: maxTextChars });
  } finally {
    await browser.close();
  }
}
