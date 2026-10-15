import fs from 'node:fs/promises';
import path from 'node:path';
import sharp from 'sharp';

export interface StoredImage {
  imagePath: string; // relative URL path under /media
  thumbPath: string;
  width: number;
  height: number;
  placeholderColor: string;
}

const MAIN_EDGE = 1080;
const THUMB_EDGE = 360;

/**
 * Re-encodes an uploaded photo into a main image and a thumbnail (WebP).
 * Re-encoding drops all metadata, including EXIF GPS location, and `rotate()`
 * applies the EXIF orientation first so photos are not sideways.
 */
export async function storePostImage(mediaDir: string, postId: string, input: Buffer): Promise<StoredImage> {
  const meta = await sharp(input, { failOn: 'error' }).metadata();
  if (!meta.width || !meta.height) throw new Error('not_an_image');
  if (meta.width < 200 || meta.height < 200) throw new Error('image_too_small');

  const dir = path.join(mediaDir, 'posts', postId);
  await fs.mkdir(dir, { recursive: true });

  const main = await sharp(input)
    .rotate()
    .resize({ width: MAIN_EDGE, height: MAIN_EDGE, fit: 'inside', withoutEnlargement: true })
    .webp({ quality: 80 })
    .toBuffer({ resolveWithObject: true });
  const thumb = await sharp(input)
    .rotate()
    .resize({ width: THUMB_EDGE, height: THUMB_EDGE, fit: 'inside', withoutEnlargement: true })
    .webp({ quality: 75 })
    .toBuffer();
  const { dominant } = await sharp(thumb).stats();

  await fs.writeFile(path.join(dir, 'main.webp'), main.data);
  await fs.writeFile(path.join(dir, 'thumb.webp'), thumb);

  const hex = (n: number) => n.toString(16).padStart(2, '0');
  return {
    imagePath: `/media/posts/${postId}/main.webp`,
    thumbPath: `/media/posts/${postId}/thumb.webp`,
    width: main.info.width,
    height: main.info.height,
    placeholderColor: `#${hex(dominant.r)}${hex(dominant.g)}${hex(dominant.b)}`,
  };
}

export async function deletePostImages(mediaDir: string, postId: string): Promise<void> {
  await fs.rm(path.join(mediaDir, 'posts', postId), { recursive: true, force: true });
}
