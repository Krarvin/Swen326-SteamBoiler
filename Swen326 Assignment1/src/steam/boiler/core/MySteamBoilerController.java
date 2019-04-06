package steam.boiler.core;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.SteamBoilerCharacteristics;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;
import steam.boiler.util.Mailbox.Mode;

public class MySteamBoilerController implements SteamBoilerController {

	/**
	 * Captures the various modes in which the controller can operate
	 *
	 * @author David J. Pearce
	 *
	 */
	private enum State {
		WAITING, READY, NORMAL, DEGRADED, RESCUE, EMERGENCY_STOP
	}

	/**
	 * Records the configuration characteristics for the given boiler problem.
	 */
	private final SteamBoilerCharacteristics configuration;

	/**
	 * Identifies the current mode in which the controller is operating.
	 */
	private State mode = State.WAITING;

	/**
	 * Construct a steam boiler controller for a given set of characteristics.
	 *
	 * @param configuration The boiler characteristics to be used.
	 */
	public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
		this.configuration = configuration;
	}

	/**
	 * This message is displayed in the simulation window, and enables a limited
	 * form of debug output. The content of the message has no material effect on
	 * the system, and can be whatever is desired. In principle, however, it should
	 * display a useful message indicating the current state of the controller.
	 *
	 * @return
	 */
	@Override
	public String getStatusMessage() {
		return mode.toString();
	}

	/**
	 * Process a clock signal which occurs every 5 seconds. This requires reading
	 * the set of incoming messages from the physical units and producing a set of
	 * output messages which are sent back to them.
	 *
	 * @param incoming The set of incoming messages from the physical units.
	 * @param outgoing Messages generated during the execution of this method should
	 *                 be written here.
	 */
	@Override
	public void clock(Mailbox incoming, Mailbox outgoing) {


		// Extract expected messages
		Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
		Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
		Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
		Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b, incoming);
		//
		double L = levelMessage.getDoubleParameter();
		double S = steamMessage.getDoubleParameter();
		int pumps = 1;
		double c = getCapacity(pumpStateMessages);
		double w = configuration.getMaximualSteamRate();

		if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages)) {
			// Level and steam messages required, so emergency stop.
			this.mode = State.EMERGENCY_STOP;
		}
		//
		//Initialization.
		if(extractOnlyMatch(MessageKind.PHYSICAL_UNITS_READY, incoming) != null) {
			this.mode = State.NORMAL;
			outgoing.send(new Message(MessageKind.MODE_m, Mode.NORMAL));
			}

		else if(this.mode == State.WAITING) {
			outgoing.send(new Message(MessageKind.MODE_m,Mode.INITIALISATION));
			if(extractOnlyMatch(MessageKind.STEAM_BOILER_WAITING, incoming) != null) {
				if(steamMessage.getDoubleParameter() != 0) {
					this.mode = State.EMERGENCY_STOP;
				}
				else if(steamMessage.getDoubleParameter() == 0) {
					if(levelMessage.getDoubleParameter() > configuration.getMaximalNormalLevel()) {
						outgoing.send(new Message(MessageKind.VALVE));
					}else if(levelMessage.getDoubleParameter() < average(configuration.getMinimalNormalLevel(), configuration.getMaximalNormalLevel())) {
						outgoing.send(new Message(MessageKind.OPEN_PUMP_n,0));
						if(configuration.getMinimalNormalLevel() > predictNextLmax(pumps,L,c,w,S)) {
						outgoing.send(new Message(MessageKind.OPEN_PUMP_n,1));
						}else {
							outgoing.send(new Message(MessageKind.CLOSE_PUMP_n,1));
						}
					}
				}

				if(levelMessage.getDoubleParameter() >= configuration.getMinimalNormalLevel() && levelMessage.getDoubleParameter() <= configuration.getMaximalNormalLevel()) {
		/*						outgoing.send(new Message(MessageKind.CLOSE_PUMP_n,1));*/
					outgoing.send(new Message(MessageKind.PROGRAM_READY));

				}
			}
		}

		//Normal Mode
		else if(this.mode == State.NORMAL) {


			if(configuration.getMinimalNormalLevel() > predictNextLmin(pumps, L, c, w, S)) {
				pumps = pumps + 2;
				openPumps(pumps,outgoing);
			}else if(configuration.getMaximalNormalLevel() >  predictNextLmin(pumps, L, c, w, S)) {
				outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 1));
			}
		}




		// FIXME: this is where the main implementation stems from

		// NOTE: this is an example message send to illustrate the syntax
	}

	public void openPumps(int pumps, Mailbox outgoing) {
		int open = 0;
		for(int i = open; i < pumps; i= i+1) {
			outgoing.send(new Message(MessageKind.OPEN_PUMP_n,i));
		}
	}



	private double predictNextLmax(int pumps, double L, double c, double w, double s) {
			double Lmax = L + (5 * c * pumps) - (5 * s);
			return Lmax;

	}

	private double predictNextLmin(int pumps, double L, double c, double w, double s) {
		double Lmin = L + (5 * c * pumps) - (5 * w);
		return Lmin;

}
	private double getCapacity(Message[] pumps) {
		double capacity = 0;
		for(int i = (int)capacity; i < pumps.length; i= i+1) {
			if(pumps[i].getBooleanParameter() == true) {
				capacity = configuration.getPumpCapacity(i);
			}
		}
		return capacity;
	}

	private double average(double a, double b) {
		return (a+b) / 2;
	}

	/**
	 * Check whether there was a transmission failure. This is indicated in several
	 * ways. Firstly, when one of the required messages is missing. Secondly, when
	 * the values returned in the messages are nonsensical.
	 *
	 * @param levelMessage      Extracted LEVEL_v message.
	 * @param steamMessage      Extracted STEAM_v message.
	 * @param pumpStates        Extracted PUMP_STATE_n_b messages.
	 * @param pumpControlStates Extracted PUMP_CONTROL_STATE_n_b messages.
	 * @return
	 */
	private boolean transmissionFailure(Message levelMessage, Message steamMessage, Message[] pumpStates,
			Message[] pumpControlStates) {
		// Check level readings
		if (levelMessage == null) {
			// Nonsense or missing level reading
			return true;
		} else if (steamMessage == null) {
			// Nonsense or missing steam reading
			return true;
		} else if (pumpStates.length != configuration.getNumberOfPumps()) {
			// Nonsense pump state readings
			return true;
		} else if (pumpControlStates.length != configuration.getNumberOfPumps()) {
			// Nonsense pump control state readings
			return true;
		}
		// Done
		return false;
	}

	/**
	 * Find and extract a message of a given kind in a mailbox. This must the only
	 * match in the mailbox, else <code>null</code> is returned.
	 *
	 * @param kind     The kind of message to look for.
	 * @param incoming The mailbox to search through.
	 * @return The matching message, or <code>null</code> if there was not exactly
	 *         one match.
	 */
	private static Message extractOnlyMatch(MessageKind kind, Mailbox incoming) {
		Message match = null;
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				if (match == null) {
					match = ith;
				} else {
					// This indicates that we matched more than one message of the given kind.
					return null;
				}
			}
		}
		return match;
	}

	/**
	 * Find and extract all messages of a given kind.
	 *
	 * @param kind     The kind of message to look for.
	 * @param incoming The mailbox to search through.
	 * @return The array of matches, which can empty if there were none.
	 */
	private static Message[] extractAllMatches(MessageKind kind, Mailbox incoming) {
		int count = 0;
		// Count the number of matches
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				count = count + 1;
			}
		}
		// Now, construct resulting array
		Message[] matches = new Message[count];
		int index = 0;
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				matches[index++] = ith;
			}
		}
		return matches;
	}
}
