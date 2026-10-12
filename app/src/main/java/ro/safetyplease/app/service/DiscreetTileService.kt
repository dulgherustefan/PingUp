package ro.safetyplease.app.service

import ro.safetyplease.app.MainActivity

/** Quick Settings tile "Ajutor discret": opens the discreet report, where one tap sends it. */
class DiscreetTileService : OpenTileService(MainActivity.OPEN_DISCREET, RequestCodes.DISCREET_TILE)
