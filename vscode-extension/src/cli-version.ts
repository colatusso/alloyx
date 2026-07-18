/** Compare dotted numeric versions. Returns <0, 0, >0 like a comparator. */
export function compareVersions(a: string, b: string): number {
  const pa = a.split(".").map((n) => parseInt(n, 10));
  const pb = b.split(".").map((n) => parseInt(n, 10));
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const x = pa[i] ?? 0;
    const y = pb[i] ?? 0;
    if (x !== y) {
      return x - y;
    }
  }
  return 0;
}

/** First MAJOR.MINOR.PATCH in text, or undefined if there is none. */
export function parseSemver(text: string): string | undefined {
  return text.match(/\d+\.\d+\.\d+/)?.[0];
}

/** Read a semantic version from GitHub's release response. */
export function parseLatestReleaseVersion(body: string): string | undefined {
  try {
    const release = JSON.parse(body) as { tag_name?: unknown };
    return typeof release.tag_name === "string" ? parseSemver(release.tag_name) : undefined;
  } catch {
    return undefined;
  }
}

/** A missing or older installed version should be updated. */
export function isCliUpdateAvailable(installed: string | undefined, latest: string): boolean {
  return !installed || compareVersions(installed, latest) < 0;
}
