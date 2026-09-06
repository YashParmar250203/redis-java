# Minimal raw-socket test client for the redis-java TCP server (Phase 4).
# No installs needed - uses .NET's TcpClient, built into PowerShell.
#
# Usage:
#   .\test-tcp.ps1 "SET name Yash"
#   .\test-tcp.ps1 "GET name"

param(
    [Parameter(Mandatory = $true)]
    [string]$Command,
    [int]$Port = 6380
)

$client = New-Object System.Net.Sockets.TcpClient("localhost", $Port)
$stream = $client.GetStream()

# Send as an inline command (plain text + CRLF) - one of the two request
# formats the server understands, alongside RESP multibulk arrays.
$bytes = [System.Text.Encoding]::UTF8.GetBytes("$Command`r`n")
$stream.Write($bytes, 0, $bytes.Length)
$stream.Flush()

Start-Sleep -Milliseconds 150

$buffer = New-Object byte[] 4096
$bytesRead = $stream.Read($buffer, 0, $buffer.Length)
$response = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $bytesRead)

$displayResponse = $response.Replace("`r`n", '\r\n')
Write-Host "Sent:     $Command"
Write-Host "Received: $displayResponse"

$client.Close()
