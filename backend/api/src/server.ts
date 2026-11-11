import { AuthController } from '#modules/auth/controllers'
import { WeatherController } from '#modules/context/controllers'
import { ImportController } from '#modules/importer/controllers'
import { AlbumController, ArtistController, SearchController, SongController } from '#modules/index'
import { LyricsController } from '#modules/lyrics/controllers'
import { PlaylistController } from '#modules/playlists/controllers'
import { RecoController } from '#modules/reco/controllers'
import { SessionsController } from '#modules/sessions/controllers'
import { StatsController } from '#modules/stats/controllers'
import { UserDataController } from '#modules/userdata/controllers'
import { App } from './app'

const app = new App([
  new SearchController(),
  new SongController(),
  new AlbumController(),
  new ArtistController(),
  new PlaylistController(),
  new RecoController(),
  new AuthController(),
  new UserDataController(),
  new ImportController(),
  new LyricsController(),
  new SessionsController(),
  new StatsController(),
  new WeatherController()
]).getApp()

export default app
