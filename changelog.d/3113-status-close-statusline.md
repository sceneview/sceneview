<!-- category: Changed -->
<!-- Maintainer note: phase 2 of the measurement-driven method refactor (#3111).
     `/status` was invoked 546 times in 30 days — ~18/day, an order of magnitude more
     than any other surface, and every project slash command measured zero. It was also
     the least useful: its own "finish the work instead of reporting" rule sat in the
     last section, after 40 lines of report format, so the model reported and stopped. -->

The Claude Code statusline now shows unsafe work: `●n` uncommitted files and `↑n`
unpushed commits, both silent when zero. These are the two signals that answer "is
anything still stranded?" — previously that question cost a full model turn to answer.
