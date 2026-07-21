import { el } from "./dom";

/** The bot face, a capsule with two dot eyes that blink at rest. */
export function buildFace(): HTMLElement {
  const face = el("span", "fr-face");
  face.append(el("span", "fr-eye"), el("span", "fr-eye"));
  return face;
}
