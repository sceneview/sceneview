<!-- category: Changed -->

- **A React Native Android model tap now carries a name.** `nodeName` went from *always* `null` to the model's file base name. An app that read `nodeName == null` as "the tap missed every model" (for instance to place an object at that point) will now see model taps stop matching that test. The type change that accompanies it is a separate entry.
