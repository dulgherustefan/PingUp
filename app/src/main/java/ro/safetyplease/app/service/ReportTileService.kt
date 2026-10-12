package ro.safetyplease.app.service

import ro.safetyplease.app.MainActivity

/** Quick Settings tile "Raportează": opens the report categories. */
class ReportTileService : OpenTileService(MainActivity.OPEN_REPORT, RequestCodes.REPORT_TILE)
