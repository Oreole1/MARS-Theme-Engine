package mars.tools;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.*;
import java.util.Observable;

import mars.*;
import mars.mips.hardware.AccessNotice;
import mars.mips.hardware.AddressErrorException;
import mars.mips.hardware.Memory;
import mars.mips.hardware.MemoryAccessNotice;
import mars.simulator.Exceptions;


/*
Copyright (c) 2003-2006,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * When enabled via bit 0 of control word 0xffff0010, this provides an external interrupt to the running program as frequently as needed.
 * Built from the KeyboardAndDisplaySimulator tool.
 */
public class InterruptTimer extends AbstractMarsToolAndApplication {

    private static String heading =  "Interrupt Timer";
    private static String version = " Version 1.0";

    private int CONTROL;
    private int DATA;
    private int currentDelaySliderAmount;
    private int currentDataDelayAmount;
    private boolean useDataAsDelay;
    private double lastTime;
    private boolean enabled;

    private DelayLengthPanel delayLengthPanel;
    private JSlider delayLengthSlider;

    /**
     * Simple constructor, likely used to run a stand-alone memory reference visualizer.
     * @param title String containing title for title bar
     * @param heading String containing text for heading shown in upper part of window.
     */
    public InterruptTimer(String title, String heading) {
        super(title,heading);
    }

    /**
     *  Simple constructor, likely used by the MARS Tools menu mechanism
     */
    public InterruptTimer() {
        super (heading+", "+version, heading);
    }


    /**
     * Main provided for pure stand-alone use.  Recommended stand-alone use is to write a
     * driver program that instantiates a MemoryReferenceVisualization object then invokes its go() method.
     * "stand-alone" means it is not invoked from the MARS Tools menu.  "Pure" means there
     * is no driver program to invoke the application.
     */
    public static void main(String[] args) {
        new InterruptTimer(heading+", "+version,heading).go();
    }


    /**
     *  Required method to return Tool name.
     *  @return  Tool name.  MARS will display this in menu item.
     */
    public String getName() {
        return "Interrupt Timer";
    }

    // Set the MMIO addresses.  Prior to MARS 3.7 these were final because
    // MIPS address space was final as well.  Now we will get MMIO base address
    // each time to reflect possible change in memory configuration. DPS 6-Aug-09
    protected void initializePreGUI() {
        CONTROL    = Memory.memoryMapBaseAddress + 16; //0xffff0010; // enable in low-order bit
        DATA       = Memory.memoryMapBaseAddress + 20; //0xffff0014; // specified time delay in milliseconds
        lastTime = (int) System.currentTimeMillis();
        enabled = false;
        useDataAsDelay = false;
    }

    protected void addAsObserver() {
        // Set transmitter Control ready bit to 1, means we're ready to accept display character.
        // We want to be an observer only of MIPS writes to DATA.
        // Use the Globals.memory.addObserver() methods instead of inherited method to achieve this.
        addAsObserver(DATA,DATA);
        // We want to be notified of each instruction execution, because instruction count is the
        // basis for delay in re-setting (literally) the TRANSMITTER_CONTROL register.  SPIM does
        // this too.  This simulates the time required for the display unit to process the
        // TRANSMITTER_DATA.
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
        addAsObserver(Memory.kernelTextBaseAddress, Memory.kernelTextLimitAddress);
    }


    /**
     *  Implementation of the inherited abstract method to build the main
     *  display area of the GUI.  It will be placed in the CENTER area of a
     *  BorderLayout.  The title is in the NORTH area, and the controls are
     *  in the SOUTH area.
     */
    protected JComponent buildMainDisplayArea() {
        JPanel mainArea = new JPanel();
        mainArea.setLayout(new BoxLayout(mainArea, BoxLayout.Y_AXIS));
        delayLengthPanel = new DelayLengthPanel();
        mainArea.add(delayLengthPanel);
        JPanel checkBoxPanel = new JPanel();
        checkBoxPanel.add(new JLabel("Use data MMIO variable 0xffff0014 as delay length (in ms)?"));
        JCheckBox checkBox = new JCheckBox();
        checkBox.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                // If checkbox is selected, use data variable as source for delay
                useDataAsDelay = e.getStateChange() == ItemEvent.SELECTED;
            }
        });
        checkBoxPanel.add(checkBox);
        mainArea.add(checkBoxPanel);

        return mainArea;
    }

    protected void processMIPSUpdate(Observable memory, AccessNotice accessNotice) {
        MemoryAccessNotice notice = (MemoryAccessNotice) accessNotice;
        // On write to DATA
        if (notice.getAccessType()==AccessNotice.WRITE && notice.getAddress() == DATA) {
            try {
                // Update value for data delay amount
                currentDataDelayAmount = Globals.memory.get(DATA, Memory.WORD_LENGTH_BYTES);
            }
            catch (AddressErrorException aee) {
                System.out.println("Tool author specified incorrect MMIO address!"+aee);
                System.exit(0);
            }
        }
        // On instruction execution in text segment
        if (notice.getAccessType()==AccessNotice.READ && Memory.inTextSegment(notice.getAddress()) && connectButton.isConnected()) {
            // Check enable bit
            enabled = getEnableBit(CONTROL) == 1;
            // Check if current time is beyond delay and if timer is enabled
            if ((int)System.currentTimeMillis() - lastTime > (useDataAsDelay ? currentDataDelayAmount : currentDelaySliderAmount) && enabled) {
                // Update lastTime
                lastTime = (int) System.currentTimeMillis();
                // Send interrupt
                mars.simulator.Simulator.externalInterruptingDevice = Exceptions.EXTERNAL_INTERRUPT_DISPLAY;
            }
        }

    }

    private static int getEnableBit(int mmioControlRegister) {
        try {
            return Globals.memory.get(mmioControlRegister, Memory.WORD_LENGTH_BYTES) & 1;
        }
        catch (AddressErrorException aee) {
            System.out.println("Tool author specified incorrect MMIO address!"+aee);
            System.exit(0);
        }
        return 0; // to satisfy the compiler -- this will never happen.
    }

    private class DelayLengthPanel extends JPanel {
        private final static int DELAY_INDEX_MIN = 0;
        private final static int DELAY_INDEX_MAX = 40;
        private final static int DELAY_INDEX_INIT = 9;
        private int[] delayTable = {
                1,    2,    3,    4,    5,   6,   7,   8,   9,   10,  20,  // 0-10
                30,  40,  50,  60,  70,  80,  90,  100,  200, 300,  //11-20
                400, 500, 600, 700, 800, 900, 1000, 2000, 3000,4000,  //21-30
                5000,6000,7000,8000,9000,10000,20000,30000,40000,50000//31-40
        };
        private JLabel sliderLabel=null;
        private volatile int delayLengthIndex = DELAY_INDEX_INIT;

        public DelayLengthPanel() {
            super(new BorderLayout());
            delayLengthSlider = new JSlider(JSlider.HORIZONTAL, DELAY_INDEX_MIN,DELAY_INDEX_MAX,DELAY_INDEX_INIT);
            delayLengthSlider.setSize(new Dimension(100,(int)delayLengthSlider.getSize().getHeight()));
            delayLengthSlider.setMaximumSize(delayLengthSlider.getSize());
            delayLengthSlider.addChangeListener(new DelayLengthListener());
            sliderLabel = new JLabel(setLabel(delayLengthIndex));
            sliderLabel.setHorizontalAlignment(JLabel.CENTER);
            sliderLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            this.add(sliderLabel, BorderLayout.NORTH);
            this.add(delayLengthSlider, BorderLayout.CENTER);
            this.setToolTipText("Parameter for simulated delay length (milliseconds)");
        }

        // returns current delay length setting, in instructions.
        public int getDelayLength() {
            return delayTable[delayLengthIndex];
        }


        // set label wording depending on current speed setting
        private String setLabel(int index) {
            return "Delay length: "+(delayTable[index])+" milliseconds";
        }


        // Both revises label as user slides and updates current index when sliding stops.
        private class DelayLengthListener implements ChangeListener {
            public void stateChanged(ChangeEvent e) {
                JSlider source = (JSlider)e.getSource();
                if (!source.getValueIsAdjusting()) {
                    delayLengthIndex = (int)source.getValue();
                    currentDelaySliderAmount = getDelayLength();
                }
                else {
                    sliderLabel.setText(setLabel(source.getValue()));
                }
            }
        }
    }
}