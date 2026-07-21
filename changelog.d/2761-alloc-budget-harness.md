<!-- category: Tests -->
- Resurrect the #2317 allocation-counting harness as a committed instrumented suite
  (`AllocationBudgetTest`): hard allocs/call ceilings on the #2263 hot-path wins —
  `slerp` pre-decomposed TRS ≤ 7, `Mat4.copyColumnsInto` = 0, Ray↔mesh ≤ 3 per
  triangle — with a permanent +1-alloc sensitivity canary so the budgets can never
  pass on a dead instrument (#2761)
