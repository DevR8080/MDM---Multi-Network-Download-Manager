# MDM---Multi-Network-Download-Manager

MDM (Multi-Network Download Manager) is an Android application that accelerates file downloads by utilizing both Wi-Fi and mobile data simultaneously. It intelligently manages multiple download sessions across available network interfaces to maximize throughput while giving users full control over bandwidth usage and network preferences.

## Features

* 🚀 **Simultaneous Multi-Network Downloads**

  * Download using both Wi-Fi and mobile data at the same time for higher transfer speeds.

* 🔄 **Dynamic Session Management**

  * Configurable number of download sessions per network.
  * Efficient distribution of download chunks across available connections.

* 📊 **Per-Network Quota Limits**

  * Set independent data usage limits for Wi-Fi and mobile networks.
  * Prevent excessive mobile data consumption.

* ⚙️ **Flexible Network Control**

  * Enable or disable multi-network mode at any time.
  * Default behavior uses both available networks for maximum performance.

* 📥 **Resumable Downloads**

  * Supports HTTP Range requests for segmented and resumable downloads.

* 📱 **Android Native**

  * Built with Kotlin.

## How It Works

MDM creates multiple download sessions and intelligently assigns them to different network interfaces. Each session downloads a different portion of the target file, allowing the application to combine the bandwidth of Wi-Fi and cellular connections. The downloaded segments are merged into a single output file once the download is complete.

## Use Cases

* Faster large file downloads
* Limited or unstable Wi-Fi connections
* Combining multiple internet connections
* Bandwidth optimization

## Requirements

* Android 9.0 (API 28) or later
* Wi-Fi and mobile data available simultaneously
* Internet permission

## Disclaimer

The achievable speed increase depends on the server's support for parallel downloads, the quality of each network connection, and device capabilities. Some servers may limit concurrent connections or not support HTTP range requests.

## License

MIT License
