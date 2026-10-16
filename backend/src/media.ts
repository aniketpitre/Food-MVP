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
  return storeImage(mediaDir, 'posts', postId, input);
}

/** Same as [storePostImage] for any kind of photo, stored under media/<kind>/<id>/. */
export async function storeImage(mediaDir: string, kind: 'posts' | 'stories' | 'places' | 'messages', id: string, input: Buffer): Promise<StoredImage> {
  const meta = await sharp(input, { failOn: 'error' }).metadata();
  if (!meta.width || !meta.height) throw new Error('not_an_image');
  if (meta.width < 200 || meta.height < 200) throw new Error('image_too_small');

  const dir = path.join(mediaDir, kind, id);
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
    imagePath: `/media/${kind}/${id}/main.webp`,
    thumbPath: `/media/${kind}/${id}/thumb.webp`,
    width: main.info.width,
    height: main.info.height,
    placeholderColor: `#${hex(dominant.r)}${hex(dominant.g)}${hex(dominant.b)}`,
  };
}

export async function deleteImages(mediaDir: string, kind: 'posts' | 'stories' | 'places' | 'messages', id: string): Promise<void> {
  await fs.rm(path.join(mediaDir, kind, id), { recursive: true, force: true });
}

export async function deletePostImages(mediaDir: string, postId: string): Promise<void> {
  await fs.rm(path.join(mediaDir, 'posts', postId), { recursive: true, force: true });
}

/** A profile photo: square, centre-cropped, 320 px WebP, metadata dropped. Returns its URL path. */
export async function storeAvatar(mediaDir: string, userId: string, input: Buffer): Promise<string> {
  const meta = await sharp(input, { failOn: 'error' }).metadata();
  if (!meta.width || !meta.height) throw new Error('not_an_image');
  if (meta.width < 64 || meta.height < 64) throw new Error('image_too_small');
  const dir = path.join(mediaDir, 'avatars');
  await fs.mkdir(dir, { recursive: true });
  // A new name on every change, so phones never show a cached old photo.
  const name = `${userId}-${Date.now().toString(36)}.webp`;
  const out = await sharp(input).rotate().resize(320, 320, { fit: 'cover', position: 'attention' }).webp({ quality: 80 }).toBuffer();
  await fs.writeFile(path.join(dir, name), out);
  return `/media/avatars/${name}`;
}

export async function deleteAvatar(mediaDir: string, urlPath: string | null): Promise<void> {
  if (!urlPath?.startsWith('/media/avatars/')) return;
  await fs.rm(path.join(mediaDir, 'avatars', path.basename(urlPath)), { force: true });
}
