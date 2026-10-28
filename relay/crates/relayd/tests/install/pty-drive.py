#!/usr/bin/env python3
"""Runs a command with a controlling terminal and types an answer when its prompt shows.

usage: pty-drive.py LOG [PATTERN=FILE]... -- COMMAND...

LOG receives everything the terminal showed. Each FILE is typed, in order, once PATTERN (a
regular expression) appears in the output since the last answer. The exit status is the
command's.
"""
import os
import pty
import re
import select
import sys

log_path, *rest = sys.argv[1:]
split = rest.index("--")
answers = []
for pair in rest[:split]:
    pattern, path = pair.split("=", 1)
    with open(path, "rb") as file:
        answers.append((re.compile(pattern.encode()), file.read()))
command = rest[split + 1:]

pid, terminal = pty.fork()
if pid == 0:
    os.execvp(command[0], command)

shown = b""
with open(log_path, "wb") as log:
    while True:
        if not select.select([terminal], [], [], 120)[0]:
            os.kill(pid, 9)
            sys.exit("pty-drive: no output for 120 seconds")
        try:
            data = os.read(terminal, 4096)
        except OSError:
            break
        if not data:
            break
        log.write(data)
        shown += data
        if answers and answers[0][0].search(shown):
            os.write(terminal, answers.pop(0)[1])
            shown = b""

_, status = os.waitpid(pid, 0)
if answers:
    sys.exit("pty-drive: the command ended before every prompt showed")
sys.exit(os.waitstatus_to_exitcode(status))
