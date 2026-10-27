package com.verdenroz.vpnchain.core.config

import com.verdenroz.vpnchain.core.model.ChainProfile
import com.verdenroz.vpnchain.core.model.DnsFilter
import com.verdenroz.vpnchain.core.model.WarpExit
import com.verdenroz.vpnchain.core.model.WarpMode
import com.verdenroz.vpnchain.core.model.WireGuardEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class SingBoxConfigGoldenTest {

    @Test
    fun `relay only proxy config with warp off is unchanged`() {
        val config = SingBoxConfigFactory.mixedProxyConfig(
            RELAY,
            clashApi = CLASH,
            warp = WARP,
            learnedWarpPath = "/run/learned-warp.json",
            learnedDirectPath = "/run/learned-direct.json",
            failOpen = true,
        )

        assertEquals(MIXED_RELAY, config)
    }

    @Test
    fun `relay only proxy config with automatic warp declares the tail and no domain rule`() {
        val config = SingBoxConfigFactory.mixedProxyConfig(
            RELAY,
            clashApi = CLASH,
            warp = WARP,
            warpMode = WarpMode.Automatic,
        )

        assertEquals(MIXED_RELAY_AUTOMATIC, config)
    }

    @Test
    fun `relay only tun config is unchanged`() {
        assertEquals(TUN_RELAY, SingBoxConfigFactory.androidChainConfig(RELAY))
    }

    @Test
    fun `relay only tun config with warp off and fail-open on is unchanged`() {
        val config = SingBoxConfigFactory.androidChainConfig(
            RELAY,
            warp = WARP,
            learnedWarpPath = "/run/learned-warp.json",
            learnedOpenPath = "/run/learned-open.json",
            failOpen = true,
            markDirect = true,
        )

        assertEquals(TUN_RELAY, config)
    }

    @Test
    fun `chain config is unchanged`() {
        val config = SingBoxConfigFactory.androidChainConfig(
            RELAY,
            ENTRY,
            clashApi = CLASH,
            dnsFilter = DnsFilter.AdsAndTrackers,
            cachePath = "/tmp/cache.db",
            allowLocalNetworks = true,
            logPath = "/tmp/relay.log",
        )

        assertEquals(TUN_CHAIN, config)
    }

    @Test
    fun `chain config with warp off and fail-open on is unchanged`() {
        val config = SingBoxConfigFactory.androidChainConfig(
            RELAY,
            ENTRY,
            clashApi = CLASH,
            dnsFilter = DnsFilter.AdsAndTrackers,
            cachePath = "/tmp/cache.db",
            warp = WARP,
            allowLocalNetworks = true,
            logPath = "/tmp/relay.log",
            learnedWarpPath = "/run/learned-warp.json",
            learnedOpenPath = "/run/learned-open.json",
            failOpen = true,
            markDirect = true,
        )

        assertEquals(TUN_CHAIN, config)
    }

    @Test
    fun `chain config with a full warp tail is unchanged`() {
        val config = SingBoxConfigFactory.androidChainConfig(
            RELAY,
            ENTRY,
            warp = WARP,
            warpMode = WarpMode.AllTraffic,
        )

        assertEquals(TUN_CHAIN_WARP, config)
    }

    @Test
    fun `relay only tun config with automatic warp declares the tail and no domain rule`() {
        val config = SingBoxConfigFactory.androidChainConfig(
            RELAY,
            warp = WARP,
            warpMode = WarpMode.Automatic,
        )

        assertEquals(TUN_RELAY_WARP, config)
    }
}

private val RELAY = ChainProfile(
    vpsIp = "203.0.113.10",
    vlessUuid = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
    realityPublicKey = "relay-public-key",
    shortId = "ab12",
    sni = "www.example.com",
)

private val ENTRY = WireGuardEntry(
    privateKey = "entry-private",
    address = "10.2.0.2/32",
    peerPublicKey = "entry-peer",
    endpointHost = "198.51.100.7",
    dns = "10.2.0.1",
)

private val WARP = WarpExit(
    privateKey = "warp-private",
    addressV4 = "172.16.0.2/32",
    addressV6 = "2606:4700::2/128",
    peerPublicKey = "warp-peer",
)

private val CLASH = ClashApi(port = 9090, secret = "s3cret")

// WARP off: the relay alone, with nothing of the tail or of detection.
private val MIXED_RELAY = """
{
    "log": {
        "level": "warn",
        "timestamp": true
    },
    "experimental": {
        "clash_api": {
            "external_controller": "127.0.0.1:9090",
            "secret": "s3cret"
        }
    },
    "inbounds": [
        {
            "type": "mixed",
            "tag": "proxy-in",
            "listen": "127.0.0.1",
            "listen_port": 1080
        }
    ],
    "outbounds": [
        {
            "type": "vless",
            "tag": "proxy",
            "server": "203.0.113.10",
            "server_port": 443,
            "uuid": "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
            "flow": "xtls-rprx-vision",
            "network": "tcp",
            "tls": {
                "enabled": true,
                "server_name": "www.example.com",
                "utls": {
                    "enabled": true,
                    "fingerprint": "chrome"
                },
                "reality": {
                    "enabled": true,
                    "public_key": "relay-public-key",
                    "short_id": "ab12"
                }
            },
            "packet_encoding": "xudp",
            "connect_timeout": "3s"
        },
        {
            "type": "direct",
            "tag": "direct"
        }
    ],
    "route": {
        "auto_detect_interface": false,
        "rules": [
            {
                "action": "sniff"
            }
        ],
        "final": "proxy"
    }
}
""".trimIndent()

private val MIXED_RELAY_AUTOMATIC = """
{
    "log": {
        "level": "warn",
        "timestamp": true
    },
    "experimental": {
        "clash_api": {
            "external_controller": "127.0.0.1:9090",
            "secret": "s3cret"
        }
    },
    "dns": {
        "servers": [
            {
                "type": "https",
                "tag": "dns-remote",
                "server": "1.1.1.1",
                "detour": "proxy"
            }
        ],
        "final": "dns-remote",
        "strategy": "ipv4_only"
    },
    "inbounds": [
        {
            "type": "mixed",
            "tag": "proxy-in",
            "listen": "127.0.0.1",
            "listen_port": 1080
        }
    ],
    "endpoints": [
        {
            "type": "wireguard",
            "tag": "warp-exit",
            "mtu": 1280,
            "address": [
                "172.16.0.2/32",
                "2606:4700::2/128"
            ],
            "private_key": "warp-private",
            "detour": "proxy",
            "peers": [
                {
                    "address": "162.159.192.1",
                    "port": 2408,
                    "public_key": "warp-peer",
                    "allowed_ips": [
                        "0.0.0.0/0",
                        "::/0"
                    ],
                    "persistent_keepalive_interval": 25
                }
            ]
        }
    ],
    "outbounds": [
        {
            "type": "vless",
            "tag": "proxy",
            "server": "203.0.113.10",
            "server_port": 443,
            "uuid": "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
            "flow": "xtls-rprx-vision",
            "network": "tcp",
            "tls": {
                "enabled": true,
                "server_name": "www.example.com",
                "utls": {
                    "enabled": true,
                    "fingerprint": "chrome"
                },
                "reality": {
                    "enabled": true,
                    "public_key": "relay-public-key",
                    "short_id": "ab12"
                }
            },
            "packet_encoding": "xudp",
            "connect_timeout": "3s"
        },
        {
            "type": "direct",
            "tag": "direct"
        }
    ],
    "route": {
        "auto_detect_interface": false,
        "rules": [
            {
                "action": "sniff"
            }
        ],
        "final": "proxy"
    }
}
""".trimIndent()

private val TUN_RELAY = """
{
    "log": {
        "level": "warn",
        "timestamp": true
    },
    "dns": {
        "servers": [
            {
                "type": "https",
                "tag": "dns-remote",
                "server": "1.1.1.1",
                "detour": "vless-proxy"
            }
        ],
        "final": "dns-remote",
        "strategy": "ipv4_only"
    },
    "inbounds": [
        {
            "type": "tun",
            "tag": "tun-in",
            "address": [
                "10.19.19.1/30",
                "fdfe:dcba:9876::1/126"
            ],
            "mtu": 1400,
            "auto_route": true,
            "strict_route": true,
            "stack": "gvisor"
        }
    ],
    "outbounds": [
        {
            "type": "vless",
            "tag": "vless-proxy",
            "server": "203.0.113.10",
            "server_port": 443,
            "uuid": "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
            "flow": "xtls-rprx-vision",
            "tls": {
                "enabled": true,
                "server_name": "www.example.com",
                "utls": {
                    "enabled": true,
                    "fingerprint": "chrome"
                },
                "reality": {
                    "enabled": true,
                    "public_key": "relay-public-key",
                    "short_id": "ab12"
                }
            },
            "packet_encoding": "xudp",
            "connect_timeout": "3s"
        },
        {
            "type": "direct",
            "tag": "direct"
        }
    ],
    "route": {
        "auto_detect_interface": true,
        "rules": [
            {
                "action": "sniff"
            },
            {
                "protocol": "dns",
                "action": "hijack-dns"
            },
            {
                "network": "udp",
                "port": 443,
                "action": "reject"
            }
        ],
        "final": "vless-proxy"
    }
}
""".trimIndent()

private val TUN_CHAIN = """
{
    "log": {
        "level": "warn",
        "timestamp": true,
        "output": "/tmp/relay.log"
    },
    "experimental": {
        "clash_api": {
            "external_controller": "127.0.0.1:9090",
            "secret": "s3cret"
        },
        "cache_file": {
            "enabled": true,
            "path": "/tmp/cache.db"
        }
    },
    "dns": {
        "servers": [
            {
                "type": "udp",
                "tag": "dns-entry",
                "server": "10.2.0.1",
                "detour": "entry-hop"
            }
        ],
        "final": "dns-entry",
        "strategy": "ipv4_only",
        "rules": [
            {
                "rule_set": [
                    "blocklist-threats",
                    "blocklist-ads",
                    "blocklist-ads-trackers"
                ],
                "action": "reject",
                "method": "default"
            }
        ]
    },
    "inbounds": [
        {
            "type": "tun",
            "tag": "tun-in",
            "address": [
                "10.19.19.1/30",
                "fdfe:dcba:9876::1/126"
            ],
            "mtu": 1280,
            "auto_route": true,
            "strict_route": true,
            "route_exclude_address": [
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16",
                "169.254.0.0/16"
            ],
            "stack": "gvisor"
        }
    ],
    "endpoints": [
        {
            "type": "wireguard",
            "tag": "entry-hop",
            "mtu": 1280,
            "address": [
                "10.2.0.2/32"
            ],
            "private_key": "entry-private",
            "peers": [
                {
                    "address": "198.51.100.7",
                    "port": 51820,
                    "public_key": "entry-peer",
                    "allowed_ips": [
                        "0.0.0.0/0"
                    ],
                    "persistent_keepalive_interval": 25
                }
            ]
        }
    ],
    "outbounds": [
        {
            "type": "vless",
            "tag": "vless-proxy",
            "server": "203.0.113.10",
            "server_port": 443,
            "uuid": "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
            "flow": "xtls-rprx-vision",
            "tls": {
                "enabled": true,
                "server_name": "www.example.com",
                "utls": {
                    "enabled": true,
                    "fingerprint": "chrome"
                },
                "reality": {
                    "enabled": true,
                    "public_key": "relay-public-key",
                    "short_id": "ab12"
                }
            },
            "packet_encoding": "xudp",
            "connect_timeout": "3s",
            "detour": "entry-hop"
        },
        {
            "type": "direct",
            "tag": "direct"
        }
    ],
    "route": {
        "auto_detect_interface": true,
        "rule_set": [
            {
                "type": "remote",
                "tag": "blocklist-threats",
                "format": "binary",
                "url": "https://github.com/Verdenroz/vpn-chain/releases/download/blocklists/threats.srs",
                "download_detour": "vless-proxy",
                "update_interval": "168h"
            },
            {
                "type": "remote",
                "tag": "blocklist-ads",
                "format": "binary",
                "url": "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs",
                "download_detour": "vless-proxy",
                "update_interval": "168h"
            },
            {
                "type": "remote",
                "tag": "blocklist-ads-trackers",
                "format": "binary",
                "url": "https://github.com/Verdenroz/vpn-chain/releases/download/blocklists/ads-trackers.srs",
                "download_detour": "vless-proxy",
                "update_interval": "168h"
            }
        ],
        "rules": [
            {
                "action": "sniff"
            },
            {
                "protocol": "dns",
                "action": "hijack-dns"
            },
            {
                "network": "udp",
                "port": 443,
                "action": "reject"
            }
        ],
        "final": "vless-proxy"
    }
}
""".trimIndent()

private val TUN_CHAIN_WARP = """
{
    "log": {
        "level": "warn",
        "timestamp": true
    },
    "dns": {
        "servers": [
            {
                "type": "udp",
                "tag": "dns-entry",
                "server": "10.2.0.1",
                "detour": "entry-hop"
            }
        ],
        "final": "dns-entry",
        "strategy": "ipv4_only"
    },
    "inbounds": [
        {
            "type": "tun",
            "tag": "tun-in",
            "address": [
                "10.19.19.1/30",
                "fdfe:dcba:9876::1/126"
            ],
            "mtu": 1280,
            "auto_route": true,
            "strict_route": true,
            "stack": "gvisor"
        }
    ],
    "endpoints": [
        {
            "type": "wireguard",
            "tag": "entry-hop",
            "mtu": 1280,
            "address": [
                "10.2.0.2/32"
            ],
            "private_key": "entry-private",
            "peers": [
                {
                    "address": "198.51.100.7",
                    "port": 51820,
                    "public_key": "entry-peer",
                    "allowed_ips": [
                        "0.0.0.0/0"
                    ],
                    "persistent_keepalive_interval": 25
                }
            ]
        },
        {
            "type": "wireguard",
            "tag": "warp-exit",
            "mtu": 1280,
            "address": [
                "172.16.0.2/32",
                "2606:4700::2/128"
            ],
            "private_key": "warp-private",
            "detour": "vless-proxy",
            "peers": [
                {
                    "address": "162.159.192.1",
                    "port": 2408,
                    "public_key": "warp-peer",
                    "allowed_ips": [
                        "0.0.0.0/0",
                        "::/0"
                    ],
                    "persistent_keepalive_interval": 25
                }
            ]
        }
    ],
    "outbounds": [
        {
            "type": "vless",
            "tag": "vless-proxy",
            "server": "203.0.113.10",
            "server_port": 443,
            "uuid": "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
            "flow": "xtls-rprx-vision",
            "tls": {
                "enabled": true,
                "server_name": "www.example.com",
                "utls": {
                    "enabled": true,
                    "fingerprint": "chrome"
                },
                "reality": {
                    "enabled": true,
                    "public_key": "relay-public-key",
                    "short_id": "ab12"
                }
            },
            "packet_encoding": "xudp",
            "connect_timeout": "3s",
            "detour": "entry-hop"
        },
        {
            "type": "direct",
            "tag": "direct"
        }
    ],
    "route": {
        "auto_detect_interface": true,
        "rules": [
            {
                "action": "sniff"
            },
            {
                "protocol": "dns",
                "action": "hijack-dns"
            },
            {
                "network": "udp",
                "port": 443,
                "action": "reject"
            }
        ],
        "final": "warp-exit"
    }
}
""".trimIndent()

private val TUN_RELAY_WARP = """
{
    "log": {
        "level": "warn",
        "timestamp": true
    },
    "dns": {
        "servers": [
            {
                "type": "https",
                "tag": "dns-remote",
                "server": "1.1.1.1",
                "detour": "vless-proxy"
            }
        ],
        "final": "dns-remote",
        "strategy": "ipv4_only"
    },
    "inbounds": [
        {
            "type": "tun",
            "tag": "tun-in",
            "address": [
                "10.19.19.1/30",
                "fdfe:dcba:9876::1/126"
            ],
            "mtu": 1280,
            "auto_route": true,
            "strict_route": true,
            "stack": "gvisor"
        }
    ],
    "endpoints": [
        {
            "type": "wireguard",
            "tag": "warp-exit",
            "mtu": 1280,
            "address": [
                "172.16.0.2/32",
                "2606:4700::2/128"
            ],
            "private_key": "warp-private",
            "detour": "vless-proxy",
            "peers": [
                {
                    "address": "162.159.192.1",
                    "port": 2408,
                    "public_key": "warp-peer",
                    "allowed_ips": [
                        "0.0.0.0/0",
                        "::/0"
                    ],
                    "persistent_keepalive_interval": 25
                }
            ]
        }
    ],
    "outbounds": [
        {
            "type": "vless",
            "tag": "vless-proxy",
            "server": "203.0.113.10",
            "server_port": 443,
            "uuid": "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0",
            "flow": "xtls-rprx-vision",
            "tls": {
                "enabled": true,
                "server_name": "www.example.com",
                "utls": {
                    "enabled": true,
                    "fingerprint": "chrome"
                },
                "reality": {
                    "enabled": true,
                    "public_key": "relay-public-key",
                    "short_id": "ab12"
                }
            },
            "packet_encoding": "xudp",
            "connect_timeout": "3s"
        },
        {
            "type": "direct",
            "tag": "direct"
        }
    ],
    "route": {
        "auto_detect_interface": true,
        "rules": [
            {
                "action": "sniff"
            },
            {
                "protocol": "dns",
                "action": "hijack-dns"
            },
            {
                "network": "udp",
                "port": 443,
                "action": "reject"
            }
        ],
        "final": "vless-proxy"
    }
}
""".trimIndent()
