<!-- category: Fixed -->
Demo app: the Contact Shadow Preview "Shadow intensity" slider no longer reads `100%`
with the thumb short of the track end. The control multiplies each context preset's own
opacity and deliberately runs past `1.0` to `1.5`, so a percentage was promising a
maximum it did not have. The readout is now the multiplier it always was — `1.00×` at
the default, `1.50×` at the end of the track (#3372).
