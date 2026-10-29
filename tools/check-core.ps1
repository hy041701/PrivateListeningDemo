$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$commonJar = Join-Path $projectRoot 'common/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar'
if (-not (Test-Path -LiteralPath $commonJar)) { throw '请先运行 :common:bundleLibCompileToJarDebug' }
$cacheRoot = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib'
$stdlib = Get-ChildItem -LiteralPath $cacheRoot -Filter 'kotlin-stdlib-*.jar' -Recurse | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $stdlib) { throw '找不到本机 Kotlin 标准库，请先构建项目。' }
$javaBin = Join-Path $env:JAVA_HOME 'bin/java.exe'
& $javaBin --class-path "$commonJar;$($stdlib.FullName)" (Join-Path $PSScriptRoot 'CoreChecks.java')
if ($LASTEXITCODE -ne 0) { throw '核心逻辑检查失败' }
