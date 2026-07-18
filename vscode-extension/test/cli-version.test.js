const assert = require("node:assert/strict");
const test = require("node:test");

const {
  compareVersions,
  isCliUpdateAvailable,
  parseLatestReleaseVersion,
} = require("../out/cli-version.js");

test("compares dotted numeric versions", () => {
  assert.equal(compareVersions("0.2.7", "0.2.7"), 0);
  assert.ok(compareVersions("0.2.10", "0.2.7") > 0);
  assert.ok(compareVersions("0.2.7", "0.3.0") < 0);
});

test("reads the latest CLI version from a GitHub release", () => {
  assert.equal(parseLatestReleaseVersion('{"tag_name":"v0.2.7"}'), "0.2.7");
  assert.equal(parseLatestReleaseVersion('{"tag_name":"release-1.4.0"}'), "1.4.0");
  assert.equal(parseLatestReleaseVersion('{"tag_name":"next"}'), undefined);
  assert.equal(parseLatestReleaseVersion("not json"), undefined);
});

test("requests an update only when the installed CLI is missing or older", () => {
  assert.equal(isCliUpdateAvailable("0.2.7", "0.2.7"), false);
  assert.equal(isCliUpdateAvailable("0.2.6", "0.2.7"), true);
  assert.equal(isCliUpdateAvailable(undefined, "0.2.7"), true);
});
