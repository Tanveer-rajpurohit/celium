$ErrorActionPreference = 'Stop'
# Brand token: CloudStream / Cloudstream, optionally followed by "-3" or " 3" (but not "3." / "3x").
# Case-sensitive so lowercase identifiers (cloudstream3, cloudstreamapp://) are never touched.
$brand = [regex]'(?<![/\.A-Za-z0-9_])Cloud[Ss]tream(?:-3|\s3(?![\.\d\w]))?'
$textNode = [regex]'(?<=>)[^<]*(?=<)'
$total = 0; $filesChanged = 0

function Save([string]$path, [string]$text, [bool]$bom) {
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($bom)))
}
function Load([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $bom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    $text = (New-Object System.Text.UTF8Encoding($false)).GetString($bytes)
    if ($bom) { $text = $text.Substring(1) }
    return @($text, $bom)
}

$xmls = Get-ChildItem app/src -Recurse -Filter strings.xml | Where-Object { $_.FullName -match '\\res\\values[^\\]*\\strings\.xml$' }
foreach ($f in $xmls) {
    $t, $bom = Load $f.FullName
    $script:n = 0
    $new = $textNode.Replace($t, { param($m) $brand.Replace($m.Value, { param($b) $script:n++; 'Celium' }) })
    if ($script:n -gt 0) { Save $f.FullName $new $bom; $total += $script:n; $filesChanged++ }
}
$xmlTotal = $total

$txts = Get-ChildItem fastlane -Recurse -File -Filter *.txt
foreach ($f in $txts) {
    $t, $bom = Load $f.FullName
    $script:n = 0
    $new = $brand.Replace($t, { param($b) $script:n++; 'Celium' })
    if ($script:n -gt 0) { Save $f.FullName $new $bom; $total += $script:n; $filesChanged++ }
}
"strings.xml replacements: $xmlTotal"
"fastlane replacements: $($total - $xmlTotal)"
"total: $total in $filesChanged files"
