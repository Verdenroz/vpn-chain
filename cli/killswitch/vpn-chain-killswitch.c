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
 *   vpn-chain-killswitch up [--allow-local] <exempt_ip> [<exempt_ip> ...]
 *   vpn-chain-killswitch down
 *
 * --allow-local additionally accepts RFC1918 and link-local destinations, so
 * Docker bridges and LAN hosts stay reachable while the chain is up.
 *
 * All exempt IPs are validated as strict IPv4 dotted-quads before touching
 * anything, and malformed input is rejected outright. `up` renders the whole
 * ruleset into one fixed nft script and loads it with a single `nft -f -`
 * transaction, so a re-arm replaces the old table with no unprotected window
 * and a failure leaves either the old table or the new one, never a partial.
 * Nothing is passed through a shell: nft is execve'd by absolute path with a
 * fixed argv and a fixed minimal environment.
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
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/capability.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

#define MAX_EXEMPT 8
#define TABLE_NAME "vpn_chain_killswitch"
#define SCRIPT_CAP 2048

static const char *NFT_PATHS[] = {"/usr/sbin/nft", "/sbin/nft", "/usr/bin/nft", NULL};

static char *const NFT_ENV[] = {(char *)"PATH=/usr/sbin:/usr/bin:/sbin:/bin", (char *)"LC_ALL=C", NULL};

/* Docker bridges, LAN, link-local. Without them the reject RSTs host-to-container
 * and host-to-LAN traffic the tunnel was never meant to carry. */
static const char *LOCAL_RANGES[] = {
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", NULL,
};

struct options {
    int allow_local;
    char **exempt_ips;
    int exempt_count;
};

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

/* Strict IPv4 dotted-quad: four decimal octets 0-255, no leading zeros, no
 * trailing garbage. Rejects anything that could be mistaken for an `nft`
 * token or smuggle unexpected content into the script. */
static int is_valid_ipv4(const char *s) {
    const char *p = s;
    for (int octet = 0; octet < 4; octet++) {
        if (octet > 0) {
            if (*p++ != '.') return 0;
        }
        if (!isdigit((unsigned char)*p)) return 0;
        const char *start = p;
        int value = 0;
        while (isdigit((unsigned char)*p)) {
            value = value * 10 + (*p++ - '0');
            if (p - start > 3) return 0;
        }
        if (value > 255 || (p - start > 1 && *start == '0')) return 0;
    }
    return *p == '\0';
}

static int emit(char *buf, size_t *len, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf + *len, SCRIPT_CAP - *len, fmt, ap);
    va_end(ap);
    if (n < 0 || (size_t)n >= SCRIPT_CAP - *len) return -1;
    *len += n;
    return 0;
}

/*
 * The whole ruleset as one nft transaction. The leading add makes the delete
 * succeed whether or not a table is left over, and the delete and re-create
 * land in the same commit as the final reject.
 *
 * tun0 traffic is already captured by sing-box for relay through the tunnel,
 * so it's not a leak. If sing-box dies, tun0 disappears with it, so that rule
 * stops matching and the exempt-IPs/reject rules take over.
 *
 * Bare `reject` (no address family) catches IPv4 and IPv6 alike, since our
 * exemptions are IPv4-only. `reject`, not `drop`, so blocked connections fail
 * fast instead of hanging other apps until their own timeout.
 */
static int build_script(char *buf, const struct options *opts) {
    size_t len = 0;
    int bad = emit(buf, &len,
        "add table inet " TABLE_NAME "\n"
        "delete table inet " TABLE_NAME "\n"
        "table inet " TABLE_NAME " {\n"
        "\tchain output {\n"
        "\t\ttype filter hook output priority 0; policy accept;\n"
        "\t\toif lo accept\n"
        "\t\toifname \"tun0\" accept\n");
    for (int i = 0; i < opts->exempt_count; i++) {
        bad |= emit(buf, &len, "\t\tip daddr %s accept\n", opts->exempt_ips[i]);
    }
    for (int i = 0; opts->allow_local && LOCAL_RANGES[i] != NULL; i++) {
        bad |= emit(buf, &len, "\t\tip daddr %s accept\n", LOCAL_RANGES[i]);
    }
    bad |= emit(buf, &len, "\t\treject\n\t}\n}\n");
    return bad ? -1 : (int)len;
}

/* Runs nft with a fixed environment, feeding `input` on stdin when given. */
static int run_nft(const char *nft_bin, char *const argv[], const char *input, size_t input_len) {
    int fds[2];
    if (input && pipe(fds) < 0) {
        perror("pipe");
        return -1;
    }
    pid_t pid = fork();
    if (pid < 0) {
        perror("fork");
        return -1;
    }
    if (pid == 0) {
        if (input) {
            close(fds[1]);
            if (dup2(fds[0], STDIN_FILENO) < 0) _exit(127);
            close(fds[0]);
        }
        execve(nft_bin, argv, NFT_ENV);
        perror("execve");
        _exit(127);
    }
    int write_failed = 0;
    if (input) {
        close(fds[0]);
        for (size_t off = 0; off < input_len;) {
            ssize_t n = write(fds[1], input + off, input_len - off);
            if (n < 0) {
                write_failed = 1;
                break;
            }
            off += n;
        }
        close(fds[1]);
    }
    int status;
    if (waitpid(pid, &status, 0) < 0) return -1;
    if (write_failed) return -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

/* Best-effort: failure (e.g. no table to delete) is fine. */
static void delete_table(const char *nft_bin) {
    char *argv[] = {(char *)"nft", (char *)"delete", (char *)"table", (char *)"inet", (char *)TABLE_NAME, NULL};
    run_nft(nft_bin, argv, NULL, 0);
}

static int parse_up_args(int argc, char *argv[], struct options *opts) {
    int i = 2;
    for (; i < argc && strncmp(argv[i], "--", 2) == 0; i++) {
        if (strcmp(argv[i], "--allow-local") == 0) {
            opts->allow_local = 1;
        } else {
            fprintf(stderr, "unknown option: %s\n", argv[i]);
            return -1;
        }
    }
    opts->exempt_ips = &argv[i];
    opts->exempt_count = argc - i;
    if (opts->exempt_count < 1) {
        fprintf(stderr, "up needs at least one exempt_ip\n");
        return -1;
    }
    if (opts->exempt_count > MAX_EXEMPT) {
        fprintf(stderr, "too many exempt IPs (max %d)\n", MAX_EXEMPT);
        return -1;
    }
    for (int j = 0; j < opts->exempt_count; j++) {
        if (!is_valid_ipv4(opts->exempt_ips[j])) {
            fprintf(stderr, "invalid exempt_ip: %s\n", opts->exempt_ips[j]);
            return -1;
        }
    }
    return 0;
}

int main(int argc, char *argv[]) {
    const char *prog = argc > 0 ? argv[0] : "vpn-chain-killswitch";

    if (argc < 2 || (strcmp(argv[1], "up") != 0 && strcmp(argv[1], "down") != 0)) {
        fprintf(
            stderr,
            "usage: %s up [--allow-local] <exempt_ip> [<exempt_ip> ...]\n"
            "       %s down\n",
            prog, prog
        );
        return 2;
    }

    int up = strcmp(argv[1], "up") == 0;
    struct options opts = {0};
    char script[SCRIPT_CAP];
    int script_len = 0;

    if (up) {
        if (parse_up_args(argc, argv, &opts) != 0) return 2;
        script_len = build_script(script, &opts);
        if (script_len < 0) {
            fprintf(stderr, "ruleset too large\n");
            return 1;
        }
#ifdef KS_PRINT_SCRIPT
        fwrite(script, 1, script_len, stdout);
        return 0;
#endif
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
        delete_table(nft_bin);
        return 0;
    }

    signal(SIGPIPE, SIG_IGN);
    char *load_argv[] = {(char *)"nft", (char *)"-f", (char *)"-", NULL};
    return run_nft(nft_bin, load_argv, script, script_len) == 0 ? 0 : 1;
}
