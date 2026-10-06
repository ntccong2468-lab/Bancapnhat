import assert from "node:assert/strict";
import { access, readFile, readdir } from "node:fs/promises";
import test from "node:test";

const root = new URL("../", import.meta.url);
const removedFrameworkName = "j" + "desk";

async function exists(relativePath) {
  try {
    await access(new URL(relativePath, root));
    return true;
  } catch {
    return false;
  }
}

async function textFiles(relativeDirectory) {
  const entries = await readdir(new URL(relativeDirectory, root), { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const relativePath = `${relativeDirectory}/${entry.name}`;
    if (entry.isDirectory()) {
      files.push(...await textFiles(relativePath));
    } else if (/\.(?:java|fxml|css|properties|md|xml|ya?ml|sh|bat|mjs)$/.test(entry.name)) {
      files.push(relativePath);
    }
  }
  return files;
}

test("JavaFX is the production desktop entrypoint", async () => {
  for (const path of [
    "src/main/java/com/tuandev/fbsbarcode/Launcher.java",
    "src/main/java/com/tuandev/fbsbarcode/MainApplication.java",
    "src/main/resources/META-INF/MANIFEST.MF",
    "src/main/resources/com/tuandev/fbsbarcode/ui/ozon/ozon-dashboard-view.fxml",
  ]) {
    assert.equal(await exists(path), true, `${path} must exist`);
  }

  const launcher = await readFile(
    new URL("src/main/java/com/tuandev/fbsbarcode/Launcher.java", root),
    "utf8",
  );
  assert.match(launcher, /Application\.launch\(MainApplication\.class/);
});

test("the retired desktop stack is absent", async () => {
  for (const path of [
    `src/${removedFrameworkName}`,
    `src/${removedFrameworkName}Test`,
    "src/legacyTest",
    "ui",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradlew",
    "gradlew.bat",
    "gradle",
    "packaging/WCode-Recovery.properties",
  ]) {
    assert.equal(await exists(path), false, `${path} must be removed`);
  }

  const authoritativeFiles = [
    "pom.xml",
    "build.sh",
    "build.bat",
    "check-portable.bat",
    "README.md",
    "CLAUDE.md",
    "SECURITY.md",
    ".gitignore",
    ...await textFiles(".github/workflows"),
    ...await textFiles("docs"),
    ...await textFiles("src/main"),
    ...await textFiles("src/test"),
  ];
  for (const file of authoritativeFiles) {
    const content = await readFile(new URL(file, root), "utf8");
    assert.doesNotMatch(content.toLowerCase(), new RegExp(removedFrameworkName), `${file} names the retired stack`);
    assert.doesNotMatch(content, /gradlew|src\/legacyTest|build\.gradle/, `${file} names retired build wiring`);
  }
});

test("Maven owns JavaFX compilation and packaging", async () => {
  const pom = await readFile(new URL("pom.xml", root), "utf8");

  assert.match(pom, /<artifactId>javafx-maven-plugin<\/artifactId>/);
  assert.match(pom, /<mainClass>com\.tuandev\.fbsbarcode\.Launcher<\/mainClass>/);
  assert.match(pom, /<artifactId>maven-jar-plugin<\/artifactId>/);
  assert.match(pom, /<artifactId>maven-dependency-plugin<\/artifactId>/);
  assert.doesNotMatch(pom, /src\/legacyTest/);
  assert.doesNotMatch(pom.toLowerCase(), new RegExp(removedFrameworkName));

  for (const artifact of [
    "javafx-controls",
    "javafx-fxml",
    "materialfx",
    "ikonli-javafx",
    "ikonli-feather-pack",
  ]) {
    const dependency = pom.match(
      new RegExp(`<dependency>[\\s\\S]*?<artifactId>${artifact}<\\/artifactId>[\\s\\S]*?<\\/dependency>`),
    )?.[0];
    assert.ok(dependency, `${artifact} dependency must exist`);
    assert.doesNotMatch(dependency, /<scope>test<\/scope>/);
  }
});

test("local build scripts invoke Maven and package the JavaFX launcher", async () => {
  const scripts = await Promise.all([
    readFile(new URL("build.sh", root), "utf8"),
    readFile(new URL("build.bat", root), "utf8"),
  ]);

  for (const script of scripts) {
    assert.match(script, /mvnw/);
    assert.match(script, /com\.tuandev\.fbsbarcode\.Launcher/);
    assert.doesNotMatch(script.toLowerCase(), new RegExp(removedFrameworkName));
    assert.doesNotMatch(script, /gradlew/);
  }
  assert.match(scripts[1], /--install-dir WCodeApp/,
    "Windows installers must not share the LocalAppData WCode data directory");
  assert.match(scripts[1], /0356BE08-487C-4E04-A2C2-353AF93DB2DE/,
    "local Windows packages must use the data-safe 1.1.10+ installer identity");
});

test("Windows CI builds a versioned downloadable JavaFX EXE without publishing a release", async () => {
  const workflow = await readFile(new URL(".github/workflows/build-java.yml", root), "utf8");

  assert.match(workflow, /build\.bat exe/);
  assert.match(workflow, /APP_VERSION=.*release-version\.mjs/);
  assert.match(workflow, /WCode-\$\{?env:APP_VERSION\}?-Ozon-Test\.exe/);
  assert.doesNotMatch(workflow, /WCode-1\.1\.10-Ozon-Test/);
  assert.match(workflow, /actions\/upload-artifact/);
  assert.doesNotMatch(workflow, /gh release|RELEASE_TOKEN/);
});

test("Znack registration test EXE is isolated, has its own update channel, and cannot update WB", async () => {
  const [workflow, buildScript, appPaths, registrationWorkflow, updateService, updateClient] = await Promise.all([
    readFile(new URL(".github/workflows/build-znack-registration-test.yml", root), "utf8"),
    readFile(new URL("build.bat", root), "utf8"),
    readFile(new URL("src/main/java/com/tuandev/fbsbarcode/shared/AppPaths.java", root), "utf8"),
    readFile(new URL("src/main/java/com/tuandev/fbsbarcode/integration/znack/registration/ZnackCardRegistrationWorkflow.java", root), "utf8"),
    readFile(new URL("src/main/java/com/tuandev/fbsbarcode/integration/update/UpdateService.java", root), "utf8"),
    readFile(new URL("src/main/java/com/tuandev/fbsbarcode/integration/update/UpdateApiClient.java", root), "utf8"),
  ]);

  assert.match(workflow, /WCODE_BUILD_PROFILE:\s*znack-registration-test/);
  assert.match(workflow, /build\.bat exe/);
  assert.match(buildScript, /--install-dir WCodeZnackRegistrationTestApp/);
  assert.match(buildScript, /-Dwcode\.data\.profile=znack-registration-test/);
  assert.match(appPaths, /WCodeZnackRegistrationTestData/);
  assert.match(appPaths, /if \(isZnackRegistrationTestProfile\(\)\) \{\s*return List\.of\(\);/);
  assert.doesNotMatch(updateService, /isZnackRegistrationTestProfile/);
  assert.match(updateClient, /https:\/\/api\.github\.com\/repos\/rupphi\/test-wcode/);
  assert.match(updateClient, /znack-registration-test-v/);
  assert.match(workflow, /gh release create/);
  assert.doesNotMatch(registrationWorkflow, /WbApiClient|cards\/update|appendGtin/);
});

test("CI builds downloadable macOS test packages for Intel and Apple Silicon", async () => {
  const workflow = await readFile(new URL(".github/workflows/build-java.yml", root), "utf8");

  assert.match(workflow, /runner:\s*macos-15-intel\s*\n\s*architecture:\s*x64/);
  assert.match(workflow, /runner:\s*macos-15\s*\n\s*architecture:\s*arm64/);
  assert.match(workflow, /jpackage --type dmg/);
  assert.match(workflow, /APP_VERSION=\$\(node tools\/release-version\.mjs\)/);
  assert.match(workflow, /WCode-\$APP_VERSION-Ozon-Test-macos-\$architecture\.dmg/);
  assert.match(workflow, /WCode-\$APP_VERSION-Ozon-Test-macos-\$architecture\.zip/);
  assert.doesNotMatch(workflow, /FBSBarcode-1\.1\.10\.jar|WCode-1\.1\.10-Ozon-Test/);
  assert.match(workflow, /surefire\.excludes=.*FxmlSmokeTest/,
    "the virtual Intel runner must avoid the unsupported in-process JavaFX harness");
  assert.match(workflow, /Contents\/MacOS\/WCode/,
    "the packaged native launcher must be smoke-tested on each Mac architecture");
  assert.match(workflow, /PRAGMA integrity_check/,
    "the packaged launcher smoke test must verify the isolated database");
  assert.doesNotMatch(workflow, /gh release|RELEASE_TOKEN/);
});

test("tagged releases publish native macOS packages for Intel and Apple Silicon", async () => {
  const workflow = await readFile(new URL(".github/workflows/release.yml", root), "utf8");

  assert.match(workflow, /runner:\s*macos-15-intel\s*\n\s*architecture:\s*x64/);
  assert.match(workflow, /runner:\s*macos-15\s*\n\s*architecture:\s*arm64/);
  assert.match(workflow, /jpackage --type dmg/);
  assert.match(workflow, /WCode-macos-\$architecture\.dmg/);
  assert.match(workflow, /WCode-macos-\$architecture\.zip/);
  assert.match(workflow, /surefire\.excludes=.*FxmlSmokeTest/);
  assert.match(workflow, /Contents\/MacOS\/WCode/);
  assert.match(workflow, /PRAGMA integrity_check/);
  assert.match(workflow, /needs:\s*\[validate, windows, macos\]/);
  for (const artifact of [
    "WCode-macos-x64.dmg",
    "WCode-macos-x64.zip",
    "WCode-macos-arm64.dmg",
    "WCode-macos-arm64.zip",
  ]) {
    assert.match(workflow, new RegExp(artifact.replaceAll(".", "\\.")));
  }
});
