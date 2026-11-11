import { z } from 'zod'

/**
 * Minimal server-side snapshot of a track. Only fields needed for library
 * display are stored; stream URLs are deliberately excluded because JioSaavn
 * media URLs expire. Clients always re-fetch fresh song details before
 * playback via GET /api/songs?ids=...
 */
export const TrackSnapshotSchema = z.object({
  id: z.string().min(1).max(64),
  name: z.string().min(1).max(512),
  artists: z.string().max(1024).optional().default(''),
  album: z.string().max(512).optional().default(''),
  imageUrl: z.string().max(2048).optional().default(''),
  duration: z.number().int().min(0).max(86_400_000).optional().nullable(),
  year: z.string().max(16).optional().nullable()
})

export type TrackSnapshot = z.infer<typeof TrackSnapshotSchema>

/**
 * Builds a sanitized snapshot from an untrusted client payload, dropping any
 * unexpected fields. Throws a zod error when required fields are missing.
 */
export function sanitizeTrackSnapshot(input: unknown): TrackSnapshot {
  return TrackSnapshotSchema.parse(input)
}
