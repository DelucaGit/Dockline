// Prepares what the Windows installer bundles next to the app (electron-builder `extraResources`):
//   build-resources/backend/dockline-backend.jar  - the Spring Boot backend (built with Maven)
//   build-resources/runtime/                      - a trimmed Java runtime made with jlink
// Run by `npm run dist`. Needs a JDK 25 (JAVA_HOME, or jlink on PATH).
import { spawnSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const desktopDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const backendDir = resolve(desktopDir, '..', 'backend')
const outDir = join(desktopDir, 'build-resources')
const win = process.platform === 'win32'

// The Java modules the backend needs. Found with
//   jdeps --print-module-deps --ignore-missing-deps --recursive --multi-release 25
// on the unpacked backend jar, plus modules that are only loaded at runtime and that jdeps
// cannot see: java.naming (Spring/Logback JNDI lookups), jdk.crypto.ec (TLS to the GitHub and
// Hostinger APIs) and jdk.zipfs (reading the nested jars).
const MODULES = [
  'java.base',
  'java.compiler',
  'java.desktop',
  'java.instrument',
  'java.management',
  'java.naming',
  'java.net.http',
  'java.prefs',
  'java.scripting',
  'java.security.jgss',
  'java.sql',
  'jdk.crypto.ec',
  'jdk.jfr',
  'jdk.security.auth',
  'jdk.unsupported',
  'jdk.zipfs'
]

function run(command, args, cwd) {
  console.log(`> ${command} ${args.join(' ')}`)
  // shell is needed on Windows to run mvnw.cmd
  const result = spawnSync(command, args, { cwd, stdio: 'inherit', shell: win })
  if (result.status !== 0) {
    console.error(`"${command}" failed (exit code ${result.status}).`)
    process.exit(1)
  }
}

function jlinkPath() {
  const exe = win ? 'jlink.exe' : 'jlink'
  const javaHome = process.env['JAVA_HOME']
  if (javaHome && existsSync(join(javaHome, 'bin', exe))) return join(javaHome, 'bin', exe)
  return exe // from PATH
}

// 1. Build the backend jar (tests run in CI and with `./mvnw verify`, so they are skipped here).
run(win ? `"${join(backendDir, 'mvnw.cmd')}"` : './mvnw',['-q', '-DskipTests', 'package'], backendDir)
const targetDir = join(backendDir, 'target')
const jar = readdirSync(targetDir).find(
  (f) => f.startsWith('dockline-backend-') && f.endsWith('.jar') && !f.endsWith('.original')
)
if (!jar) {
  console.error(`No backend jar found in ${targetDir}.`)
  process.exit(1)
}
rmSync(outDir, { recursive: true, force: true })
mkdirSync(join(outDir, 'backend'), { recursive: true })
copyFileSync(join(targetDir, jar), join(outDir, 'backend', 'dockline-backend.jar'))

// 2. Build the trimmed Java runtime. --compress=zip-0 (stored, not zipped) on purpose: the
// installer compresses everything with LZMA, which shrinks an unzipped runtime much better.
// Measured: installer 172 MB with zip-0 versus 185 MB with zip-9.
run(
  `"${jlinkPath()}"`,
  [
    '--add-modules',
    MODULES.join(','),
    '--strip-debug',
    '--no-man-pages',
    '--no-header-files',
    '--compress=zip-0',
    '--output',
    `"${join(outDir, 'runtime')}"`
  ],
  desktopDir
)
console.log(`Backend and Java runtime ready in ${outDir}`)
