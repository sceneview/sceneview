<!-- category: Fixed -->
- **Android demo: Rerun's dense scan draws thinner walls and drops the dust.** Each depth sample now counts by how confident ARCore is, how near it was and how face-on it saw the surface, so a close look outweighs a distant, grazing one. A saved scan also keeps only the surfaces at least two depth frames saw, which removes the stray points one noisy frame left in the air.
