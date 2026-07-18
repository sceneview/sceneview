<!-- category: Fixed -->
- `play_listing.py` accepted abbreviated flags: `--appl` expanded to `--apply`
  via argparse prefix matching and reached the Play Console write path. Both
  store-sync scripts now require exact flag names, which also keeps the new
  `--apply-screenshots` upload unreachable by a near-miss (#2612).
