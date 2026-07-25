/*
 * vpn-chain-killswitch.c
 *
 * Narrow, single-purpose privileged helper: installs or removes an nftables
 * OUTPUT-chain kill switch that REJECTs all outbound traffic except loopback
 * and a short list of exempt destination IPs (the VPS and, when configured,
 * the Proton WireGuard endpoint). This is the desktop kill switch for the
 * case ProtonVPN's own kill switch can't cover - when sing-box dials the
 * WireGuard entry hop itself (no real ProtonVPN app/tunnel in the loop for
 * its kill switch to attach to).
 *
 * A firewall rule, not a route-table blackhole: sing-box's
 * `auto_detect_interface` watches the routing table via netlink to pick the
 * real uplink interface, and a blackhole route (no interface attached)
 * confuses that detection. nftables leaves the routing table untouched.
 *
 * Usage:
 *   vpn-chain-killswitch up <exempt_ip> [<exempt_ip> ...]
 *   vpn-chain-killswitch down
 *
 * All exempt IPs are validated as strict IPv4 dotted-quads before touching
 * anything - nothing is passed through a shell (every `nft` invocation uses
 * execv with a fixed argv array), and malformed input is rejected outright.
 *
 * This binary should be the ONLY thing carrying CAP_NET_ADMIN via setcap: it
 * raises the capability into its own ambient set and execs the ordinary,
 * unprivileged `nft` binary, which inherits it only for these calls. `nft`
 * itself is never modified or granted any capability of its own.
 *
 * One-time setup (see cli/killswitch/build.sh):
 *   gcc -O2 -Wall -o vpn-chain-killswitch vpn-chain-killswitch.c -lcap
 *   sudo setcap cap_net_admin=eip ./vpn-chain-killswitch
 */

#include <ctype.h>
#include <stdio.h>
#include <string.h>
#include <sys/capability.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

#define MAX_EXEMPT 8
#define TABLE_NAME "vpn_chain_killswitch"

static const char *NFT_PATHS[] = {"/usr/sbin/nft", "/sbin/nft", "/usr/bin/nft", NULL};

static const char *find_nft(void) {
    for (int i = 0; NFT_PATHS[i] != NULL; i++) {
        if (access(NFT_PATHS[i], X_OK) == 0) return NFT_PATHS[i];
    }
    return NULL;
}

/*
 * File capabilities give this process CAP_NET_ADMIN in permitted+effective
 * at exec, but not inheritable (inherited unchanged from the parent shell).
 * Ambient capabilities need both, so raise our own permitted cap into our
 * inheritable set first - a process may always rearrange caps it holds.
 */
static int add_net_admin_to_inheritable(void) {
    cap_t caps = cap_get_proc();
    if (!caps) return -1;
    cap_value_t cap = CAP_NET_ADMIN;
    int ok = cap_set_flag(caps, CAP_INHERITABLE, 1, &cap, CAP_SET) == 0
        && cap_set_proc(caps) == 0;
    cap_free(caps);
    return ok ? 0 : -1;
}

/* Strict IPv4 dotted-quad check - rejects anything that could be mistaken
 * for an `nft` token or smuggle unexpected content. */
static int is_valid_ipv4(const char *s) {
    int dots = 0, digits = 0;
    if (!*s) return 0;
    for (const char *p = s; *p; p++) {
        if (*p == '.') {
            if (digits == 0) return 0;
            dots++;
            digits = 0;
            if (dots > 3) return 0;
        } else if (isdigit((unsigned char)*p)) {
            digits++;
            if (digits > 3) return 0;
        } else {
            return 0;
        }
    }
    return dots == 3 && digits > 0;
}

static int run_nft(const char *nft_bin, char *const argv[]) {
    pid_t pid = fork();
    if (pid < 0) {
        perror("fork");
        return -1;
    }
    if (pid == 0) {
        execv(nft_bin, argv);
        perror("execv");
        _exit(127);
    }
    int status;
    if (waitpid(pid, &status, 0) < 0) return -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

/* Best-effort: tears down any existing table so `up` is idempotent even if a
 * prior session left one behind. Failure (e.g. it doesn't exist) is fine. */
static void delete_table(const char *nft_bin) {
    char *argv[] = {(char *)"nft", (char *)"delete", (char *)"table", (char *)"inet", (char *)TABLE_NAME, NULL};
    run_nft(nft_bin, argv);
}

int main(int argc, char *argv[]) {
    const char *prog = argc > 0 ? argv[0] : "vpn-chain-killswitch";

    if (argc < 2 || (strcmp(argv[1], "up") != 0 && strcmp(argv[1], "down") != 0)) {
        fprintf(
            stderr,
            "usage: %s up <exempt_ip> [<exempt_ip> ...]\n"
            "       %s down\n",
            prog, prog
        );
        return 2;
    }

    int up = strcmp(argv[1], "up") == 0;
    char **exempt_ips = &argv[2];
    int exempt_count = argc - 2;

    if (up) {
        if (exempt_count < 1) {
            fprintf(stderr, "up needs at least one exempt_ip\n");
            return 2;
        }
        if (exempt_count > MAX_EXEMPT) {
            fprintf(stderr, "too many exempt IPs (max %d)\n", MAX_EXEMPT);
            return 2;
        }
        for (int i = 0; i < exempt_count; i++) {
            if (!is_valid_ipv4(exempt_ips[i])) {
                fprintf(stderr, "invalid exempt_ip: %s\n", exempt_ips[i]);
                return 2;
            }
        }
    }

    if (add_net_admin_to_inheritable() != 0 ||
        prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_RAISE, CAP_NET_ADMIN, 0, 0) != 0) {
        fprintf(
            stderr,
            "could not acquire CAP_NET_ADMIN - run once:\n"
            "  sudo setcap cap_net_admin=eip %s\n",
            prog
        );
        return 1;
    }

    const char *nft_bin = find_nft();
    if (!nft_bin) {
        fprintf(stderr, "could not find the 'nft' binary on this system\n");
        return 1;
    }

    if (!up) {
        delete_table(nft_bin); /* best-effort */
        return 0;
    }

    int rc = 0;
    delete_table(nft_bin); /* idempotent: clear any stale table first */

    char *table_argv[] = {(char *)"nft", (char *)"add", (char *)"table", (char *)"inet", (char *)TABLE_NAME, NULL};
    if (run_nft(nft_bin, table_argv) != 0) rc = 1;

    char *chain_argv[] = {
        (char *)"nft", (char *)"add", (char *)"chain", (char *)"inet", (char *)TABLE_NAME, (char *)"output",
        (char *)"{", (char *)"type", (char *)"filter", (char *)"hook", (char *)"output",
        (char *)"priority", (char *)"0", (char *)";", (char *)"policy", (char *)"accept", (char *)";", (char *)"}",
        NULL,
    };
    if (run_nft(nft_bin, chain_argv) != 0) rc = 1;

    char *lo_argv[] = {
        (char *)"nft", (char *)"add", (char *)"rule", (char *)"inet", (char *)TABLE_NAME, (char *)"output",
        (char *)"oif", (char *)"lo", (char *)"accept", NULL,
    };
    if (run_nft(nft_bin, lo_argv) != 0) rc = 1;

    /* tun0 traffic is already captured by sing-box for relay through the
     * tunnel, so it's not a leak. If sing-box dies, tun0 disappears with it,
     * so this rule stops matching and the exempt-IPs/reject rules take over. */
    char *tun_argv[] = {
        (char *)"nft", (char *)"add", (char *)"rule", (char *)"inet", (char *)TABLE_NAME, (char *)"output",
        (char *)"oifname", (char *)"tun0", (char *)"accept", NULL,
    };
    if (run_nft(nft_bin, tun_argv) != 0) rc = 1;

    for (int i = 0; i < exempt_count; i++) {
        char *ip_argv[] = {
            (char *)"nft", (char *)"add", (char *)"rule", (char *)"inet", (char *)TABLE_NAME, (char *)"output",
            (char *)"ip", (char *)"daddr", exempt_ips[i], (char *)"accept", NULL,
        };
        if (run_nft(nft_bin, ip_argv) != 0) rc = 1;
    }

    /* Bare `reject` (no address family) catches IPv4 and IPv6 alike, since our
     * exemptions are IPv4-only. `reject`, not `drop`, so blocked connections
     * fail fast instead of hanging other apps until their own timeout. */
    char *reject_argv[] = {
        (char *)"nft", (char *)"add", (char *)"rule", (char *)"inet", (char *)TABLE_NAME, (char *)"output",
        (char *)"reject", NULL,
    };
    if (run_nft(nft_bin, reject_argv) != 0) rc = 1;

    return rc;
}
