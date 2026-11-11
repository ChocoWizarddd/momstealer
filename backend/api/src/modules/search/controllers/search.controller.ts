import { createRoute, OpenAPIHono, z } from '@hono/zod-openapi'
import {
  SearchAlbumModel,
  SearchArtistModel,
  SearchModel,
  SearchPlaylistModel,
  SearchSongModel
} from '#modules/search/models'
import { SearchService } from '#modules/search/services'
import { SongModel } from '#modules/songs/models'
import type { Routes } from '#common/types'

export class SearchController implements Routes {
  public controller: OpenAPIHono
  private searchService: SearchService

  constructor() {
    this.controller = new OpenAPIHono()
    this.searchService = new SearchService()
  }

  public initRoutes() {
    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/search',
        tags: ['Search'],
        summary: 'Global search',
        description: 'Search for songs, albums, artists, and playlists based on the provided query string.',
        operationId: 'globalSearch',
        request: {
          query: z.object({
            query: z.string().openapi({
              title: 'Search query',
              description: 'Search query',
              type: 'string',
              example: 'Imagine Dragons'
            })
          })
        },
        responses: {
          200: {
            description: 'Successful global search',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean().openapi({
                    description: 'Indicates whether the search was successful',
                    type: 'boolean',
                    example: true
                  }),
                  data: SearchModel.openapi({
                    description: 'Search results including songs, albums, artists, and playlists'
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const { query } = ctx.req.valid('query')

        const result = await this.searchService.searchAll(query)

        return ctx.json({ success: true, data: result })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/search/songs',
        tags: ['Search'],
        summary: 'Search for songs',
        description: 'Search for songs based on the provided query',
        operationId: 'searchSongs',
        request: {
          query: z.object({
            query: z.string().openapi({
              title: 'Search query',
              description: 'Search query for songs',
              type: 'string',
              example: 'Believer'
            }),
            page: z.string().pipe(z.coerce.number()).optional().openapi({
              title: 'Page Number',
              description: 'The page number of the search results to retrieve',
              type: 'integer',
              example: '0',
              default: '0'
            }),
            limit: z.string().pipe(z.coerce.number()).optional().openapi({
              title: 'Limit',
              description: 'Number of search results per page',
              type: 'integer',
              example: '10',
              default: '10'
            }),
            debug: z.string().optional().openapi({
              title: 'Debug',
              description: 'Set to true for a candidate-pool diagnostic block (_debug)',
              type: 'string',
              example: 'true'
            })
          })
        },
        responses: {
          200: {
            description: 'Successful response with song search results',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean().openapi({
                    description: 'Indicates whether the song search was successful',
                    type: 'boolean',
                    example: true
                  }),
                  data: SearchSongModel.openapi({
                    description: 'Search results for songs'
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const { query, page, limit, debug } = ctx.req.valid('query')

        // Ranking (intent + version + playCount + provider score over a fused
        // getResults + autocomplete pool) happens inside the use case, which
        // is the single ranking authority for song search.
        if (debug === 'true' || debug === '1') {
          const { payload, trace } = await this.searchService.searchSongsWithTrace({
            query,
            page: page || 0,
            limit: limit || 10
          })
          return ctx.json({ success: true, data: payload, _debug: trace })
        }
        const result = await this.searchService.searchSongs({ query, page: page || 0, limit: limit || 10 })

        return ctx.json({ success: true, data: result })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/search/radio',
        tags: ['Search'],
        summary: 'Related-song radio',
        description:
          'Returns OTHER songs related to the seed track (primary artist + album ' +
          'pools, deduplicated, near-duplicate remixes/covers removed). Used for ' +
          'auto-next and same-genre shuffle.',
        operationId: 'radioSongs',
        request: {
          query: z.object({
            seedId: z.string().max(64).optional(),
            seedName: z.string().max(200).optional(),
            artists: z.string().max(300).optional(),
            album: z.string().max(300).optional(),
            limit: z.string().pipe(z.coerce.number()).optional().openapi({ default: '30' })
          })
        },
        responses: {
          200: {
            description: 'Related songs for radio playback',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: SearchSongModel.openapi({ description: 'Related songs' })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const { seedId, seedName, artists, album, limit } = ctx.req.valid('query')
        const result = await this.searchService.radioSongs({
          seedId,
          seedName,
          artists,
          album,
          limit: limit ?? 30
        })
        return ctx.json({ success: true, data: result })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/search/trending',
        tags: ['Search'],
        summary: 'Trending now',
        description:
          'Real trending data from JioSaavn: the songs inside the top chart ' +
          '(e.g. Most Searched Songs), newly trending albums, and the chart list.',
        operationId: 'trending',
        responses: {
          200: {
            description: 'Trending songs, albums and charts',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean(),
                  data: z.object({
                    songs: z.array(SongModel),
                    albums: z.array(
                      z.object({
                        id: z.string(),
                        title: z.string(),
                        subtitle: z.string(),
                        image: z.string(),
                        url: z.string(),
                        language: z.string(),
                        year: z.string()
                      })
                    ),
                    charts: z.array(
                      z.object({
                        id: z.string(),
                        title: z.string(),
                        image: z.string(),
                        url: z.string()
                      })
                    ),
                    chartTitle: z.string(),
                    fetchedAt: z.number()
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const result = await this.searchService.trendingSongs()
        return ctx.json({ success: true, data: result })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/search/albums',
        tags: ['Search'],
        summary: 'Search for albums',
        description: 'Search for albums based on the provided query',
        operationId: 'searchAlbums',
        request: {
          query: z.object({
            query: z.string().openapi({
              description: 'Search query for albums',
              type: 'string',
              example: 'Evolve'
            }),
            page: z.string().pipe(z.coerce.number()).optional().openapi({
              description: 'The page number of the search results to retrieve',
              type: 'integer',
              example: '0',
              default: '0'
            }),
            limit: z.string().pipe(z.coerce.number()).optional().openapi({
              description: 'The number of search results per page',
              type: 'integer',
              example: '10',
              default: '10'
            })
          })
        },
        responses: {
          200: {
            description: 'Successful response with album search results',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean().openapi({
                    description: 'Indicates whether the album search was successful',
                    type: 'boolean',
                    example: true
                  }),
                  data: SearchAlbumModel.openapi({
                    description: 'Search results for albums'
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const { query, page, limit } = ctx.req.valid('query')

        const result = await this.searchService.searchAlbums({ query, page: page || 0, limit: limit || 10 })

        return ctx.json({ success: true, data: result })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/search/artists',
        tags: ['Search'],
        summary: 'Search for artists',
        description: 'Search for artists based on the provided query',
        operationId: 'searchArtists',
        request: {
          query: z.object({
            query: z.string().openapi({
              title: 'Search query',
              description: 'Search query for artists',
              type: 'string',
              example: 'Adele'
            }),
            page: z.string().pipe(z.coerce.number()).optional().openapi({
              title: 'Page Number',
              description: 'The page number of the search results to retrieve',
              type: 'integer',
              example: '0',
              default: '0'
            }),
            limit: z.string().pipe(z.coerce.number()).optional().openapi({
              title: 'Limit',
              description: 'Number of search results per page',
              type: 'integer',
              example: '10',
              default: '10'
            })
          })
        },
        responses: {
          200: {
            description: 'Successful response with artist search results',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean().openapi({
                    description: 'Indicates whether the artist search was successful',
                    type: 'boolean',
                    example: true
                  }),
                  data: SearchArtistModel.openapi({
                    description: 'Search results for artists'
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const { query, page, limit } = ctx.req.valid('query')

        const result = await this.searchService.searchArtists({ query, page: page || 0, limit: limit || 10 })

        return ctx.json({ success: true, data: result })
      }
    )

    this.controller.openapi(
      createRoute({
        method: 'get',
        path: '/search/playlists',
        tags: ['Search'],
        summary: 'Search for playlists',
        description: 'Search for playlists based on the provided query',
        operationId: 'searchPlaylists',
        request: {
          query: z.object({
            query: z.string().openapi({
              title: 'Search query',
              description: 'Search query for playlists',
              type: 'string',
              example: 'Indie'
            }),
            page: z.string().pipe(z.coerce.number()).optional().openapi({
              title: 'Page Number',
              description: 'The page number of the search results to retrieve',
              type: 'integer',
              example: '0',
              default: '0'
            }),
            limit: z.string().pipe(z.coerce.number()).optional().openapi({
              title: 'Limit',
              description: 'Number of search results per page',
              type: 'integer',
              example: '10',
              default: '10'
            })
          })
        },
        responses: {
          200: {
            description: 'Successful response with playlist search results',
            content: {
              'application/json': {
                schema: z.object({
                  success: z.boolean().openapi({
                    description: 'Indicates whether the playlist search was successful',
                    type: 'boolean',
                    example: true
                  }),
                  data: SearchPlaylistModel.openapi({
                    description: 'Search results for playlist'
                  })
                })
              }
            }
          }
        }
      }),
      async (ctx) => {
        const { query, page, limit } = ctx.req.valid('query')

        const result = await this.searchService.searchPlaylists({ query, page: page || 0, limit: limit || 10 })

        return ctx.json({ success: true, data: result })
      }
    )
  }
}
