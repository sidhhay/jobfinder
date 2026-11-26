package com.jobfinder.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.jobfinder.web.ApiException;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Renders user-editable HTML in an isolated browser context. */
@Service
public class PdfService {

    private static final int MAX_HTML_CHARS = 1_000_000;

    public String sanitize(String html) {
        if (html == null || html.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Document HTML cannot be empty");
        }
        if (html.length() > MAX_HTML_CHARS) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Document HTML is too large");
        }
        Safelist allowlist = Safelist.relaxed()
                .addTags("style")
                .addAttributes(":all", "style")
                .addAttributes("a", "target")
                .addProtocols("a", "http", "https", "mailto");
        return Jsoup.clean(html, "", allowlist, new Document.OutputSettings().prettyPrint(false));
    }

    public byte[] render(String html) {
        String safeHtml = sanitize(html);
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = launch(playwright);
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setJavaScriptEnabled(false))) {
            Page page = context.newPage();
            // Prevent HTML/CSS from reaching arbitrary network or local resources.
            page.route("**/*", route -> route.abort());
            page.setContent(safeHtml,
                    new Page.SetContentOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            return page.pdf(new Page.PdfOptions()
                    .setFormat("Letter")
                    .setPrintBackground(true)
                    .setMargin(new Margin().setTop("0").setBottom("0").setLeft("0").setRight("0")));
        }
    }

    private static Browser launch(Playwright playwright) {
        PlaywrightException last = null;
        for (String channel : new String[] {null, "msedge", "chrome"}) {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            if (channel != null) options.setChannel(channel);
            try {
                return playwright.chromium().launch(options);
            } catch (PlaywrightException error) {
                last = error;
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PDF export needs a browser. Run: mvn exec:java "
                + "-D exec.mainClass=com.microsoft.playwright.CLI -D exec.args=\"install chromium\"", last);
    }
}
