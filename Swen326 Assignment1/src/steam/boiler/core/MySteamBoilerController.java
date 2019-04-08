package steam.boiler.core;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;
import steam.boiler.util.Mailbox.Mode;
import steam.boiler.util.SteamBoilerCharacteristics;

/**
 * Main class for SteamBoiler implementation. Initialisation and normal mode work correctly,
 * however rescue and degrade mode still have problems.
 * not all tests pass.
 */

public class MySteamBoilerController implements SteamBoilerController {
  /**
   * the amount of pumps on at any given time.
   */
  boolean[] pumpsOn;

  /**
   * Captures the various modes in which the controller can operate.
   *
   * @author David J. Pearce
   *
   */

  private enum State {
    /**
     * steam boiler waiting.
     */
    WAITING,
    /**
     * steam boiler ready.
     */
    READY,
    /**
     * steam boiler normalizing the water level.
     */
    NORMAL,
    /**
     * a physical unit is degraded.
     */
    DEGRADED,
    /**
     * tries to fix a physical unit.
     */
    RESCUE,
    /**
     * steam boiler enters a state which must be immediately stopped.
     */
    EMERGENCY_STOP
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
   * @param configuration
   *          The boiler characteristics to be used.
   */
  public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
    this.configuration = configuration;
    this.pumpsOn = new boolean[configuration.getNumberOfPumps()];
  }

  /**
   * This message is displayed in the simulation window, and enables a limited
   * form of debug output. The content of the message has no material effect on
   * the system, and can be whatever is desired. In principle, however, it should
   * display a useful message indicating the current state of the controller.
   *
   * @return the message.
   */
  @Override
  public String getStatusMessage() {
    String string = this.mode.toString();
    assert string != null;
    return string;
  }

  /**
   * Process a clock signal which occurs every 5 seconds. This requires reading
   * the set of incoming messages from the physical units and producing a set of
   * output messages which are sent back to them.
   *
   * @param incoming
   *          The set of incoming messages from the physical units.
   * @param outgoing
   *          Messages generated during the execution of this method should be
   *          written here.
   */
  @Override
  public void clock(@NonNull Mailbox incoming, @NonNull Mailbox outgoing) {
    // Extract expected messages
    Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
    Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b,
        incoming);
    //
    assert (levelMessage != null);
    assert (steamMessage != null);
    if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages,
        pumpControlStateMessages)) {
      // Level and steam messages required, so emergency stop.
      this.mode = State.EMERGENCY_STOP;
      outgoing.send(new Message(MessageKind.MODE_m, Mode.EMERGENCY_STOP));
      return;
    }

    double l = levelMessage.getDoubleParameter();
    double s = steamMessage.getDoubleParameter();
    double c = this.configuration.getPumpCapacity(0);
    double w = this.configuration.getMaximualSteamRate();

    if (checkSteamRate(s) && this.mode != State.NORMAL) {
      this.mode = State.EMERGENCY_STOP;
      outgoing.send(new Message(MessageKind.MODE_m, Mode.EMERGENCY_STOP));
      return;
    }
    if (checkWaterLevel(l) && this.mode != State.NORMAL) {
      this.mode = State.EMERGENCY_STOP;
      outgoing.send(new Message(MessageKind.MODE_m, Mode.EMERGENCY_STOP));
      return;
    }

    if (checkPumpControllers(incoming, outgoing)) {
      outgoing.send(new Message(MessageKind.MODE_m, Mode.DEGRADED));
      this.mode = State.DEGRADED;
    }
    //
    // Initialization.

    if (this.mode == State.EMERGENCY_STOP) {
      outgoing.send(new Message(MessageKind.MODE_m, Mode.EMERGENCY_STOP));
    } else if (extractOnlyMatch(MessageKind.PHYSICAL_UNITS_READY, incoming) != null) {
      this.mode = State.NORMAL;
      outgoing.send(new Message(MessageKind.MODE_m, Mode.NORMAL));
    } else if (this.mode == State.WAITING) {
      outgoing.send(new Message(MessageKind.MODE_m, Mode.INITIALISATION));

      if (extractOnlyMatch(MessageKind.STEAM_BOILER_WAITING, incoming) != null) {

        if (steamMessage.getDoubleParameter() != 0) {
          this.mode = State.EMERGENCY_STOP;
        }
        if (steamMessage.getDoubleParameter() == 0) {
          if (levelMessage.getDoubleParameter() >= this.configuration.getMinimalNormalLevel()
              && levelMessage.getDoubleParameter() <= this.configuration.getMaximalNormalLevel()) {
            /* outgoing.send(new Message(MessageKind.CLOSE_PUMP_n,1)); */
            outgoing.send(new Message(MessageKind.PROGRAM_READY));
          } else if (levelMessage.getDoubleParameter()
              >= this.configuration.getMaximalNormalLevel()) {
            outgoing.send(new Message(MessageKind.VALVE));
          } else if (levelMessage.getDoubleParameter()
              <= this.configuration.getMaximalLimitLevel()) {
            fillBoiler(outgoing);
          }
        }
      }
    } else if (this.mode == State.NORMAL) {
      if (checkWaterLevel(l)) {
        this.mode = State.RESCUE;
      } else if (checkWithinLimits(l)) {

        outgoing.send(new Message(MessageKind.MODE_m, Mode.EMERGENCY_STOP));
        this.mode = State.EMERGENCY_STOP;
      } else if (checkPumpControllers(incoming, outgoing)) {
        outgoing.send(new Message(MessageKind.MODE_m, Mode.DEGRADED));
        this.mode = State.DEGRADED;
      } else if (checkSteamRate(s)) {
        outgoing.send(new Message(MessageKind.MODE_m, Mode.DEGRADED));
        outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
        this.mode = State.DEGRADED;
      } else if (checkPumps(incoming, outgoing)) {
        checkPumps(incoming, outgoing);
      }
      openPumps(predictPumps(l, c, w, s, this.configuration.getMinimalNormalLevel(),
          this.configuration.getMaximalNormalLevel()), outgoing);

    }
    if (this.mode == State.RESCUE) {
      if (transmissionFailure(levelMessage, steamMessage,
          pumpStateMessages, pumpControlStateMessages)) {
        // Level and steam messages required, so emergency stop.
        this.mode = State.EMERGENCY_STOP;
        outgoing.send(new Message(MessageKind.MODE_m, Mode.EMERGENCY_STOP));
        return;
      }
      outgoing.send(new Message(MessageKind.MODE_m, Mode.RESCUE));
      outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));

    } else if (this.mode == State.DEGRADED) {

      for (int i = 0; i < incoming.size(); i++) {
        Message m = incoming.read(i);
        if (m.getKind().equals(MessageKind.STEAM_REPAIRED)) {
          outgoing.send(new Message(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT));
          outgoing.send(new Message(MessageKind.MODE_m, Mode.NORMAL));
          this.mode = State.NORMAL;
        } else if (m.getKind().equals(MessageKind.PUMP_CONTROL_REPAIRED_n)) {
          outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_ACKNOWLEDGEMENT_n,
              m.getIntegerParameter()));
          outgoing.send(new Message(MessageKind.MODE_m, Mode.NORMAL));
          this.mode = State.NORMAL;
        } else if (m.getKind().equals(MessageKind.PUMP_REPAIRED_n)) {
          outgoing.send(new Message(MessageKind.PUMP_REPAIRED_ACKNOWLEDGEMENT_n,
              m.getIntegerParameter()));
          outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, m.getIntegerParameter()));
          outgoing.send(new Message(MessageKind.MODE_m, Mode.NORMAL));
          this.mode = State.NORMAL;
        }
      }
    }

    // NOTE: this is an example message send to illustrate the syntax
  }

  /**
   * fills the boiler by opening pumps.
   *
   * @param outgoing
   *          The set of incoming messages from the physical units.
   */

  public static void fillBoiler(Mailbox outgoing) {
    int open = 0;
    int numPumps = 3;
    for (int i = open; i < numPumps; i = i + 1) {
      outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
    }
  }

  /**
   * fills the boiler by opening pumps.
   *
   * @param outgoing
   *          Messages generated during the execution of this method should be
   *          written here.
   */

  public void closePumps(Mailbox outgoing) {
    int open = 0;
    for (int i = open; i < this.configuration.getNumberOfPumps(); i = i + 1) {
      outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
    }
  }

  /**
   * opens the amount of pumps passed into the method. and closes the remaining
   * pumps open
   *
   * @param pumps
   *          the number of pumps to open.
   * @param outgoing
   *          Messages generated during the execution of this method should be
   *          written here.
   */

  public void openPumps(int pumps, Mailbox outgoing) {
    if (pumps != 0) {
      int open = 0;
      for (int i = open; i < pumps; i = i + 1) {
        outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
        this.pumpsOn[i] = true;
      }
      for (int i = pumps; i <= this.configuration.getNumberOfPumps(); i = i + 1) {
        outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
        if (i < this.configuration.getNumberOfPumps()) {
          this.pumpsOn[i] = false;
        }
      }
    } else {
      for (int i = 0; i < this.configuration.getNumberOfPumps(); i = i + 1) {
        outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
        this.pumpsOn[i] = false;
      }
    }
  }

  /**
   * predicts the amount of pumps the steam boiler should open for the next cycle.
   *
   * @param l
   *          the current level of water.
   * @param c
   *          the capacity of a pump.
   * @param w
   *          the maximum steam rate.
   * @param s
   *          the current steam reading.
   * @param normalmin
   *          the min normal of config.
   * @param normalmax
   *          the max normal of config.
   * @return the amount of predicted pumps to turn on.
   */

  public int predictPumps(double l, double c, double w,
      double s, double normalmin, double normalmax) {
    int count = 0;
    double dist = 1000000;
    for (int i = count; i <= this.configuration.getNumberOfPumps(); i = i + 1) {
      if (getDist(average(predictNextLmax(i, l, c, w, s), predictNextLmin(i, l, c, w, s)),
          average(normalmin, normalmax)) <= dist) {
        dist = getDist(average(predictNextLmax(i, l, c, w, s), predictNextLmin(i, l, c, w, s)),
            average(normalmin, normalmax));
        count = i;
      }
    }

    return count;
  }

  /**
   * checks if the steam reading is faulty.
   *
   * @param steamRate
   *          the current steam rate.
   * @return true if the steam rate is faulty
   */
  private boolean checkSteamRate(double steamRate) {
    if (steamRate < 0 || steamRate > this.configuration.getMaximualSteamRate()) {
      return true;
    }
    return false;
  }

  /**
   * checks if water level is faulty.
   *
   * @param waterLevel
   *          current water level.
   * @return true if the water level is faulty.
   */
  private boolean checkWaterLevel(double waterLevel) {
    if (waterLevel < 0 || waterLevel > this.configuration.getCapacity()) {
      return true;
    }
    return false;
  }

  /**
   * checks that the current water level is within the min and max limits.
   *
   * @param waterLevel
   *          the current water level.
   * @return true if the water level is not within limits.
   */
  private boolean checkWithinLimits(double waterLevel) {
    if (waterLevel < this.configuration.getMinimalLimitLevel()
        && waterLevel < this.configuration.getMaximalLimitLevel()) {
      return true;
    }
    return false;
  }

  /**
   * checks all pump controllers with their respective pumps to see if there are
   * faults.
   *
   * @param incoming
   *          the incoming mailbox.
   * @param outgoing
   *          the outgoing mailbox.
   * @return true if there is a controller fault.
   */
  private static boolean checkPumpControllers(Mailbox incoming, Mailbox outgoing) {
    for (int i = 0; i < incoming.size(); i++) {
      Message tempPump = incoming.read(i);
      if (tempPump.getKind().equals(MessageKind.PUMP_STATE_n_b)) {
        int pumpNum = tempPump.getIntegerParameter();
        boolean pumpOn = tempPump.getBooleanParameter();
        for (int j = 0; j < incoming.size(); j++) {
          Message tempCont = incoming.read(j);
          if (tempCont.getKind().equals(MessageKind.PUMP_CONTROL_STATE_n_b)) {
            int contNum = tempCont.getIntegerParameter();
            boolean contOn = tempCont.getBooleanParameter();
            if (pumpNum == contNum && (pumpOn != contOn)) {
              outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, contNum));
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  /**
   * checks if there are faults in the pumps if they do not match the expected
   * readings.
   *
   * @param incoming
   *          incoming mailbox.
   * @param outgoing
   *          outgoing mailbox.
   * @return true if there is a pump fault.
   */
  private boolean checkPumps(Mailbox incoming, Mailbox outgoing) {

    for (int i = 0; i < incoming.size(); i++) {
      Message m = incoming.read(i);
      if (m.getKind().equals(MessageKind.PUMP_STATE_n_b)) {
        if (this.pumpsOn[m.getIntegerParameter()] != m.getBooleanParameter()) {
          this.pumpsOn[m.getIntegerParameter()] = false;
          outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, m.getIntegerParameter()));
          return true;
        }
      }
    }
    return false;
  }

  /**
   * returns the distance between two numbers.
   *
   * @param a
   *          the first distance
   * @param b
   *          the second distance
   * @return the absolute distance of the two values.
   */
  private static double getDist(double a, double b) {
    double dist = 0;
    if (a > b) {
      dist = a - b;
    }
    if (b > a) {
      dist = b - a;
    }
    return dist;
  }

  /**
   * predicts the lmax of the next clock cycle.
   *
   * @param pumps
   *          the number of pumps.
   * @param l
   *          the current level.
   * @param c
   *          the pump capacity.
   * @param w
   *          the max steam rate.
   * @param s
   *          the current steam rate.
   * @return the predicted lmax.
   */
  private static double predictNextLmax(int pumps, double l, double c, double w, double s) {
    double lmax = l + (5 * c * pumps) - (5 * s);
    return lmax;

  }

  /**
   * predicts the lmin of the next clock cycle.
   *
   * @param pumps
   *          the number of pumps.
   * @param l
   *          the current level.
   * @param c
   *          the pump capacity.
   * @param w
   *          the max steam rate.
   * @param s
   *          the current steam rate.
   * @return the predicted lmin.
   */

  private static double predictNextLmin(int pumps, double l, double c, double w, double s) {
    double lmin = l + (5 * c * pumps) - (5 * w);
    return lmin;

  }

  /**
   * predicts the amount of pumps the steam boiler should open for intialization.
   *
   * @param l
   *          the current level of water.
   * @param c
   *          the capacity of a pump.
   * @param w
   *          the maximum steam rate.
   * @param s
   *          the current steam reading.
   * @param min
   *          the lowest normal.
   * @param max
   *          the highest normal.
   * @return the predicted numbers of pumps needed to initiate steamboiler.
   */

  public int predictPumpsInit(double l, double c, double w, double s, double min, double max) {
    int count = 0;
    double dist = 1000000;
    for (int i = count; i <= this.configuration.getNumberOfPumps(); i = i + 1) {
      if (getDist(average(predictNextLmax(i, l, c, w, s),
          predictNextInit(i, l, c)), average(min, max)) <= dist) {
        dist = getDist(average(predictNextLmax(i, l, c, w, s),
            predictNextInit(i, l, c)), average(min, max));
        count = i;
      }
    }

    return count;
  }

  /**
   * returns the expected next cycle during initiation mode.
   *
   * @param pumps
   *          number of pumps
   * @param l
   *          current level
   * @param c
   *          pump capacity
   * @return the lmin.
   */
  private static double predictNextInit(int pumps, double l, double c) {
    double lmin = l + (5 * c * pumps) - (5 * 0);
    return lmin;

  }

  /**
   * the average between two numbers.
   *
   * @param a
   *          first number.
   * @param b
   *          second number.
   * @return the average between two doubles.
   */

  private static double average(double a, double b) {
    return (a + b) / 2;
  }

  /**
   * Check whether there was a transmission failure. This is indicated in several
   * ways. Firstly, when one of the required messages is missing. Secondly, when
   * the values returned in the messages are nonsensical.
   *
   * @param levelMessage
   *          Extracted LEVEL_v message.
   * @param steamMessage
   *          Extracted STEAM_v message.
   * @param pumpStates
   *          Extracted PUMP_STATE_n_b messages.
   * @param pumpControlStates
   *          Extracted PUMP_CONTROL_STATE_n_b messages.
   * @return if there was a transmission failure.
   */
  private boolean transmissionFailure(Message levelMessage,
      @Nullable Message steamMessage, Message[] pumpStates,
      Message[] pumpControlStates) {
    if (steamMessage == null) {
      // Nonsense or missing steam reading
      return true;
    } else if (pumpStates.length != this.configuration.getNumberOfPumps()) {
      // Nonsense pump state readings
      return true;
    } else if (pumpControlStates.length != this.configuration.getNumberOfPumps()) {
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
   * @param kind
   *          The kind of message to look for.
   * @param incoming
   *          The mailbox to search through.
   * @return The matching message, or <code>null</code> if there was not exactly
   *         one match.
   */
  private static @Nullable Message extractOnlyMatch(MessageKind kind, Mailbox incoming) {
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
   * @param kind
   *          The kind of message to look for.
   * @param incoming
   *          The mailbox to search through.
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


