import type { Properties } from "./types";

type Capture = (event: string, properties?: Properties) => void;

/**
 * Wires the auto-captured page view, click, and error events. Element text,
 * input values, and error messages never leave the page.
 */
export function startAutocapture(capture: Capture): void {
  const pageView = () => capture("fr.page_view", { path: location.pathname });

  for (const method of ["pushState", "replaceState"] as const) {
    const original = history[method].bind(history);
    history[method] = (...args: Parameters<History["pushState"]>) => {
      original(...args);
      pageView();
    };
  }

  addEventListener("popstate", pageView);
  pageView();

  addEventListener(
    "click",
    (e) => {
      const target = e.target instanceof Element ? e.target : null;
      const control = target?.closest("a,button,[role=button]");
      if (!control) return;

      const properties: Properties = { tag: control.tagName.toLowerCase() };
      if (control.id) properties.id = control.id;

      capture("fr.click", properties);
    },
    true,
  );

  addEventListener("error", (e) => {
    capture("fr.error", { source: e.filename || "unknown", line: e.lineno || 0 });
  });

  addEventListener("unhandledrejection", () => {
    capture("fr.error", { source: "unhandledrejection", line: 0 });
  });
}
