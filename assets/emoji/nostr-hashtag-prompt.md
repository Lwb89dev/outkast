# Nostr hashtag ostrich

Generated with the built-in imagegen tool, with alpha transparency enabled.

Master (1254 px): `assets/emoji/nostr-hashtag-ostrich-master.png`.
Bundled asset: `app/src/main/res/drawable-nodpi/nostr_hashtag_ostrich.png`, the master downscaled to 256 px
(`magick master.png -filter Lanczos -resize 256x256 -strip PNG32:nostr_hashtag_ostrich.png`) so each inline
use decodes a ~256 KB bitmap instead of ~6 MB.
The app displays the silhouette at 1.3 em and tints it uniformly `#8B5CF6`.

## Generation prompt

Use case: logo-brand
Asset type: tiny inline hashtag emoji for an Android Nostr app.
Primary request: one extremely simplified full-body silhouette of an ostrich running, entirely solid purple, on a genuinely transparent background.
Subject: clearly recognizable ostrich in side profile, long curved neck, small head with short pointed beak, rounded compact body, small tail, exactly two long strong legs in a dynamic running stride, legs visibly separated with a generous transparent gap. Entire animal including feet and head fully visible.
Style: flat filled silhouette, bold smooth clean contours, very few shapes, optimized to remain recognizable at 20–24 pixels tall.
Color palette: a single uniform vivid Nostr purple #8B5CF6 across the entire silhouette, antialiased edge pixels allowed.
Composition: centered on a square canvas, animal fills about 90 percent of canvas, minimal even transparent padding.
Constraints: no eye, no internal markings, no feathers or fine details, no outlines, no gradients, no shading, no dimensional lighting, no text, no decorative elements, no motion lines, no ground, no shadow, no border. Actual alpha transparency.

## Refinement prompt

Use case: precise-object-edit
Edit target: the purple running ostrich silhouette just generated.
Keep the exact full-body ostrich pose, proportions, orientation, framing, and shape. Clean this into a crisp tiny UI emoji: every interior pixel must be a single flat solid purple #8B5CF6, no gradients, no lighting, no texture. Remove every stray fragment, speckle, blue fringe, noise, and detached pixel outside the silhouette. Only the one clean filled ostrich silhouette should remain on an actual fully transparent alpha background, with smooth antialiased edges and no halo. Keep the full head, beak, neck, body, tail, both legs, and both feet, fully visible. No added detail, no eye, no text, no ground or shadow. It must read well at 24 pixels tall.
