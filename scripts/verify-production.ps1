param(
    [string]$BaseUrl = "https://d2n7ih9kfxbzvd.cloudfront.net",
    [string]$RealtimeHost = "d2n7ih9kfxbzvd.cloudfront.net",
    [string]$OutputPath = "$PSScriptRoot\..\build\reports\production-verification.txt"
)

# Public, read-only verification. Never supply a real credential to this script.
# Defaults match app/build.gradle.kts; override only with a public HTTPS origin.
$ErrorActionPreference = "Stop"
$origin = [Uri]$BaseUrl
if ($origin.Scheme -ne 'https' -or $origin.UserInfo -or $origin.Query -or $origin.Fragment) {
    throw 'BaseUrl must be a public HTTPS URL without credentials, query, or fragment.'
}
Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(20)
$results = [System.Collections.Generic.List[string]]::new()
$results.Add("UTC: $([DateTime]::UtcNow.ToString('o'))")
$results.Add("Origin: $($origin.GetLeftPart([UriPartial]::Authority)); no response bodies retained")

function Check-Http {
    param([string]$Path, [int[]]$Expected, [string]$Mode = 'anonymous')
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, "$($BaseUrl.TrimEnd('/'))$Path")
    $response = $null
    try {
        if ($Mode -eq 'invalid-bearer') {
            $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', "invalid-audit-$([Guid]::NewGuid().ToString('N'))")
        }
        $response = $client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        $ok = $Expected -contains $status
        $results.Add("[$(if($ok){'PASS'}else{'FAIL'})] $Mode $Path - HTTP $status; expected $($Expected -join '/')")
    } catch {
        # Exception messages and bodies can contain sensitive data: record only type.
        $results.Add("[FAIL] $Mode $Path - transport $($_.Exception.GetType().Name)")
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
    }
}

try {
    Check-Http '/health' @(200)
    Check-Http '/' @(200)
    Check-Http '/v1/version' @(200)
    foreach ($path in @('/v1/account', '/v1/onboarding', '/v1/events?limit=1', '/v1/location-sessions/active', '/v1/people', '/v1/reviews', '/v1/memories')) {
        Check-Http $path @(401,403)
        Check-Http $path @(401,403) 'invalid-bearer'
    }
    try {
        $addresses = [System.Net.Dns]::GetHostAddresses($RealtimeHost)
        if ($addresses.Count -eq 0) { throw 'No address' }
        $results.Add("[PASS] realtime-dns - $RealtimeHost resolves (DNS only; no WebSocket acceptance claim)")
    } catch {
        $results.Add("[FAIL] realtime-dns - $RealtimeHost does not resolve")
    }
} finally {
    $client.Dispose()
    $handler.Dispose()
}
$reportDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$results | Set-Content -LiteralPath $OutputPath -Encoding utf8
$results
if ($results | Where-Object { $_ -match '^\[FAIL\]' }) { exit 1 }
