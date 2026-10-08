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
    "src/main/java/com/vncode/app/Launcher.java",
    "src/main/java/com/vncode/app/MainApplication.java",
    "src/main/resources/META-INF/MANIFEST.MF",
    "src/main/resources/com/vncode/app/ui/ozon/ozon-dashboard-view.fxml",
  ]) {
    assert.equal(await exists(path), true, `${path} must exist`);
  }

  const launcher = await readFile(
    new URL("src/main/java/com/vncode/app/Launcher.java", root),
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
    "packaging/VN-code-Recovery.properties",
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
  assert.match(pom, /<mainClass>com\.vncode\.app\.Launcher<\/mainClass>/);
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
    assert.match(script, /com\.vncode\.app\.Launcher/);
    assert.doesNotMatch(script.toLowerCase(), new RegExp(removedFrameworkName));
    assert.doesNotMatch(script, /gradlew/);
  }
  assert.match(scripts[1], /--install-dir VNcodeApp/,
    "Windows installers must not share the LocalAppData VN code data directory");
  assert.match(scripts[1], /8CBBA0E2-6E73-4F56-9101-6BC0948D3C72/,
    "local packages must use the independent VN code installer identity");
});

test("Windows CI builds a versioned downloadable JavaFX EXE without publishing a release", async () => {
  const workflow = await readFile(new URL(".github/workflows/build-java.yml", root), "utf8");

  assert.match(workflow, /build\.bat exe/);
  assert.match(workflow, /APP_VERSION=.*release-version\.mjs/);
  assert.match(workflow, /VN-code-\$\{?env:APP_VERSION\}?-Windows-x64\.exe/);
  assert.doesNotMatch(workflow, /VN-code-1\.1\.10-Ozon-Test/);
  assert.match(workflow, /actions\/upload-artifact/);
  assert.doesNotMatch(workflow, /gh release|RELEASE_TOKEN/);
});

test("manual Windows installer builds can skip the macOS jobs", async () => {
  const workflow = await readFile(new URL(".github/workflows/build-java.yml", root), "utf8");
  assert.match(workflow, /workflow_dispatch:\s*\n\s*inputs:\s*\n\s*windows_only:/);
  assert.match(workflow, /windows_only:[\s\S]*?type:\s*boolean\s*\n\s*default:\s*true/);
  assert.match(workflow, /build-macos-test:\s*\n\s*if:\s*\$\{\{\s*github\.event_name != 'workflow_dispatch' \|\| !inputs\.windows_only\s*\}\}/);
});

test("Znack registration test EXE is isolated, has its own update channel, and cannot update WB", async () => {
  const [workflow, buildScript, appPaths, registrationWorkflow, updateService, updateClient] = await Promise.all([
    readFile(new URL(".github/workflows/build-znack-registration-test.yml", root), "utf8"),
    readFile(new URL("build.bat", root), "utf8"),
    readFile(new URL("src/main/java/com/vncode/app/shared/AppPaths.java", root), "utf8"),
    readFile(new URL("src/main/java/com/vncode/app/integration/znack/registration/ZnackCardRegistrationWorkflow.java", root), "utf8"),
    readFile(new URL("src/main/java/com/vncode/app/integration/update/UpdateService.java", root), "utf8"),
    readFile(new URL("src/main/java/com/vncode/app/integration/update/UpdateApiClient.java", root), "utf8"),
  ]);

  assert.match(workflow, /VNCODE_BUILD_PROFILE:\s*znack-registration-test/);
  assert.match(workflow, /build\.bat exe/);
  assert.match(buildScript, /--install-dir VNcodeZnackRegistrationTestApp/);
  assert.match(buildScript, /-Dvncode\.data\.profile=znack-registration-test/);
  assert.match(appPaths, /VNcodeZnackRegistrationTestData/);
  assert.match(appPaths, /legacyAppDataDirs\(\)\s*\{[\s\S]*?return List\.of\(\);/);
  assert.doesNotMatch(updateService, /isZnackRegistrationTestProfile/);
  assert.match(updateClient, /https:\/\/api\.github\.com\/repos\/ntccong2468-lab\/Vncode/);
  assert.match(updateClient, /znack-registration-test-v/);
  assert.match(workflow, /github\.repository == 'ntccong2468-lab\/Vncode'/,
    'the isolated publisher must match the actual client update repository');
  assert.match(workflow, /gh release create/);
  assert.doesNotMatch(registrationWorkflow, /WbApiClient|cards\/update|appendGtin/);
});

test("CI builds downloadable macOS test packages for Intel and Apple Silicon", async () => {
  const workflow = await readFile(new URL(".github/workflows/build-java.yml", root), "utf8");

  assert.match(workflow, /runner:\s*macos-15-intel\s*\n\s*architecture:\s*x64/);
  assert.match(workflow, /runner:\s*macos-15\s*\n\s*architecture:\s*arm64/);
  assert.match(workflow, /jpackage --type dmg/);
  assert.match(workflow, /APP_VERSION=\$\(node tools\/release-version\.mjs\)/);
  assert.match(workflow, /VN-code-\$APP_VERSION-Ozon-Test-macos-\$architecture\.dmg/);
  assert.match(workflow, /VN-code-\$APP_VERSION-Ozon-Test-macos-\$architecture\.zip/);
  assert.doesNotMatch(workflow, /VNcode-1\.1\.10\.jar|VN-code-1\.1\.10-Ozon-Test/);
  assert.match(workflow, /surefire\.excludes=.*FxmlSmokeTest/,
    "the virtual Intel runner must avoid the unsupported in-process JavaFX harness");
  assert.match(workflow, /Contents\/MacOS\/VN code/,
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
  assert.match(workflow, /VN-code-macos-\$architecture\.dmg/);
  assert.match(workflow, /VN-code-macos-\$architecture\.zip/);
  assert.match(workflow, /surefire\.excludes=.*FxmlSmokeTest/);
  assert.match(workflow, /Contents\/MacOS\/VN code/);
  assert.match(workflow, /PRAGMA integrity_check/);
  assert.match(workflow, /needs:\s*\[validate, windows, macos\]/);
  for (const artifact of [
    "VN-code-macos-x64.dmg",
    "VN-code-macos-x64.zip",
    "VN-code-macos-arm64.dmg",
    "VN-code-macos-arm64.zip",
  ]) {
    assert.match(workflow, new RegExp(artifact.replaceAll(".", "\\.")));
  }
});

// A personal fork must not package the original vendor entitlement/report clients.
test("the personal app has no original license server dependency", async () => {
  assert.equal(await exists("src/main/java/com/vncode/app/integration/license"), false);
  assert.equal(await exists("src/main/java/com/vncode/app/ui/license"), false);
  for (const file of await textFiles("src/main")) {
    const content = await readFile(new URL(file, root), "utf8");
    assert.doesNotMatch(content, /wcode\.online|LicenseDialogService|LicenseService/, file);
  }
});
