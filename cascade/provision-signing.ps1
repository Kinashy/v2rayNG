[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $BackupDirectory,
    [Parameter(Mandatory)][string] $GhPath
)
$ErrorActionPreference='Stop'
$config=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'distribution.json') | ConvertFrom-Json
$backup=[IO.Path]::GetFullPath($BackupDirectory)
$null=New-Item -ItemType Directory -Path $backup -Force
$identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
$aclOutput=& icacls.exe $backup /inheritance:r /grant:r "${identity}:(OI)(CI)F" 2>&1
if($LASTEXITCODE -ne 0){throw 'Could not restrict access to the signing backup directory.'}
$keyPath=Join-Path $backup 'cascade-release.p12'
$metadataPath=Join-Path $backup 'signing-backup.json'
if((Test-Path -LiteralPath $keyPath) -xor (Test-Path -LiteralPath $metadataPath)) {
    throw 'Incomplete signing backup; refusing to regenerate an existing key.'
}
if(-not (Test-Path -LiteralPath $keyPath)) {
    $password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $rsa=[Security.Cryptography.RSA]::Create(3072)
    try {
        $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new(
            'CN=Cascade VPN, O=Kinashy', $rsa,
            [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        $certificate=$request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddDays(-1),[DateTimeOffset]::UtcNow.AddYears(50))
        try {
            $certificate.FriendlyName='cascade'
            [IO.File]::WriteAllBytes($keyPath,$certificate.Export([Security.Cryptography.X509Certificates.X509ContentType]::Pkcs12,$password))
            [IO.File]::WriteAllBytes((Join-Path $backup 'cascade-release.cer'),$certificate.RawData)
            $certificateHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($certificate.RawData)).ToLowerInvariant()
        } finally { $certificate.Dispose() }
    } finally { $rsa.Dispose() }
    $metadata=[ordered]@{schema=1;repository=$config.repository;alias='cascade';password=$password;certificate_sha256=$certificateHash;keystore_sha256=(Get-FileHash -LiteralPath $keyPath -Algorithm SHA256).Hash.ToLowerInvariant()}
    [IO.File]::WriteAllText($metadataPath,($metadata | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
$metadata=Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
if($metadata.repository -cne $config.repository -or $metadata.keystore_sha256 -cne (Get-FileHash -LiteralPath $keyPath -Algorithm SHA256).Hash.ToLowerInvariant()) {
    throw 'Signing backup identity or checksum does not match.'
}
$secretValues=[ordered]@{
    CASCADE_KEYSTORE_BASE64=[Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath))
    CASCADE_KEYSTORE_PASSWORD=$metadata.password
    CASCADE_KEY_ALIAS=$metadata.alias
}
foreach($secret in $secretValues.GetEnumerator()) {
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$GhPath
    foreach($argument in @('secret','set',$secret.Key,'--repo',$config.repository)){$start.ArgumentList.Add($argument)}
    $start.UseShellExecute=$false
    $start.CreateNoWindow=$true
    $start.RedirectStandardInput=$true
    $start.RedirectStandardOutput=$true
    $start.RedirectStandardError=$true
    $process=[Diagnostics.Process]::Start($start)
    try {
        $process.StandardInput.Write([string]$secret.Value)
        $process.StandardInput.Close()
        $stdout=$process.StandardOutput.ReadToEndAsync()
        $stderr=$process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        if($process.ExitCode -ne 0){throw "Could not configure repository secret $($secret.Key)."}
    } finally {$process.Dispose()}
    'Configured signing secret: '+$secret.Key
}
'Signing certificate SHA-256: '+$metadata.certificate_sha256
'Signing backup directory: '+$backup
