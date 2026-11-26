package com.jobfinder.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.jobfinder.web.ApiException;
import org.springframework.http.HttpStatus;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Prints HTML to a Letter sized PDF with headless Chromium. The browser is installed once by
 * the setup script, so it is not downloaded while a request is waiting.
 */
@Service
public class PdfService {

    /** Playwright objects are not thread safe, so each call uses its own instance. */
    public byte[] render(String html) {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = launch(playwright)) {
            Page page = browser.newPage();
            page.setContent(html, new Page.SetContentOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            return page.pdf(new Page.PdfOptions()
                    .setFormat("Letter")
                    .setPrintBackground(true)
                    .setMargin(new Margin().setTop("0").setBottom("0").setLeft("0").setRight("0")));
        }
    }

    /**
     * Uses Playwright's own Chromium when the setup script installed it; otherwise falls back to
     * the Edge or Chrome already on the machine so PDF export still works.
     */
    private static Browser launch(Playwright playwright) {
        PlaywrightException last = null;
        for (String channel : new String[] {null, "msedge", "chrome"}) {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            if (channel != null) {
                options.setChannel(channel);
            }
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
