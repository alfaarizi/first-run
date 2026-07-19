# Groundedness rubric

The judge scores one answer against the chunks it cited. The score is the
citation check from `docs/evaluation.md`: an answer is grounded only when the
docs carry it.

## Checks

An answer passes only if all three hold:

1. **Every factual claim traces to a cited chunk.** Each statement about the
   product (steps, limits, prices, behavior) appears in, or follows directly
   from, the text of a chunk the answer cited. General pleasantries and
   restating the question carry no claims and need no citation.
2. **The citations are real.** Every cited source is one of the chunks
   retrieved for the answer, quoted faithfully. A citation to a page that
   says something else fails.
3. **Missing knowledge is admitted.** When the docs lack the answer, the
   answer says so and offers the support channel. For these, inventing an
   answer fails even if it happens to be true of the product.

## Verdicts

- `grounded`: all three checks hold.
- `ungrounded`: any claim lacks doc support, cites falsely, or the answer
  guesses where the docs are silent.

An unanswerable question answered with an honest "the docs do not cover
this" plus a support pointer scores `grounded` (IDK-correct counts as
correct). The judge sees the question, the streamed answer with its
citations (each carrying the cited text), and the reference row, and returns
one verdict with a one-sentence reason.
