<!-- category: Changed -->
- Demo apps (Android, iOS): analytics no longer record purchases. The `support_prompt_purchase_launched` and `support_prompt_purchase_pending` events are gone, with their `product_id`, and the support sheet's result no longer names an amount or a payment outcome (`tier_*`, `purchase_*`). A visit to the store sheet now reads `store`, whatever happened there.
