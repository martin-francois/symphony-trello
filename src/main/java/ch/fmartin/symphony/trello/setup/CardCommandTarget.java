package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.util.function.UnaryOperator;

/// What one `symphony-trello card` command works on: the selected connected board and its
/// resolved workflow configuration, which carries the Trello credentials. `credentialContext` adds
/// the credential file and variable names to an authentication failure, as `start` does, without
/// adding the credential values.
record CardCommandTarget(
        ConnectedBoard board, EffectiveConfig config, UnaryOperator<TrelloBoardSetupException> credentialContext) {}
