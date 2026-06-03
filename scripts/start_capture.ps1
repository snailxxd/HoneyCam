param(
    [string]$Interface = "Ethernet",
    [string]$OutDir = "pcap"
)

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outFile = Join-Path $OutDir "honeycam-$timestamp.pcapng"

if (-not (Test-Path $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir | Out-Null
}

Write-Host "Starting packet capture on interface: $Interface"
Write-Host "Output file: $outFile"
Write-Host "Use Ctrl+C to stop capture."

# Requires Wireshark/tshark in PATH.
tshark -i $Interface -f "tcp port 80 or tcp port 554" -w $outFile
