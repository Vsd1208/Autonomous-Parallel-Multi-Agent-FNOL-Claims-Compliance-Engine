/** Photo upload helpers shared by the claim form and the claimant's upload page. */

export const MAX_PHOTO_BYTES = 5 * 1024 * 1024;
/** Photos are downscaled in the browser so a full set stays small (phone photos are often 3-8 MB). */
export const MAX_PHOTO_EDGE = 1600;

/**
 * Reads an uploaded damage photo as a JPEG data: URI, downscaled to MAX_PHOTO_EDGE on the longer side.
 * The API sends it to the vision model and stores it for the adjuster.
 */
export function readPhoto(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    if (!/^image\/(jpeg|png|webp|gif)$/.test(file.type)) {
      reject(new Error('Photo must be a JPEG, PNG, WebP or GIF image'));
      return;
    }
    if (file.size > 20 * 1024 * 1024) {
      reject(new Error('Photo must be 20 MB or smaller'));
      return;
    }
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('Could not read the photo'));
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error('Could not read the photo'));
      img.onload = () => {
        const scale = Math.min(1, MAX_PHOTO_EDGE / Math.max(img.width, img.height));
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(img.width * scale);
        canvas.height = Math.round(img.height * scale);
        canvas.getContext('2d')!.drawImage(img, 0, 0, canvas.width, canvas.height);
        const uri = canvas.toDataURL('image/jpeg', 0.85);
        if (uri.length * 0.75 > MAX_PHOTO_BYTES) reject(new Error('Photo is too large even after resizing'));
        else resolve(uri);
      };
      img.src = String(reader.result);
    };
    reader.readAsDataURL(file);
  });
}
