# Third-party components

## USBtinLib 1.2.0

- Author: Thomas Fischl, https://www.fischl.de/usbtin/#usbtinlib
- License: GNU Lesser General Public License v3.0, see [COPYING.LESSER](COPYING.LESSER) and [COPYING](COPYING)
- Source code: https://github.com/EmbedME/USBtinLib
- Included unmodified as `src/main/resources/USBtinLib-1.2.0.jar`, it can be replaced by an own build of the library.
  `SynchronizedUSBtin` extends the class `USBtin` without changing the library.

## Elster index table (can_progs)

- Author: Jürg Müller, CH-5524, http://juerg5524.ch/list_data.php
- License: GNU Lesser General Public License v3.0 (header of `KElsterTable.cpp` in `can_progs.zip` of 2020-03-29)
- `src/main/java/nrw/andresen/stbl/services/can/ElsterTable.java` is a Java translation of `ElsterTable.inc`, with
  names and types corrected and added for the Stiebel Eltron WPL 17 ACS classic.
