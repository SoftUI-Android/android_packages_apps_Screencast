/*
 * <!--
 *    Copyright (C) 2014 The NamelessRom Project
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * -->
 */

package org.namelessrom.screencast;

import android.content.Context;

public class Utils {

    public static void setRecording(final Context context, final boolean isRecording) {
        context.getSharedPreferences("preferences", 0)
                .edit()
                .putBoolean("recording", isRecording)
                .apply();
    }

    public static boolean isRecording(final Context context) {
        return context.getSharedPreferences("preferences", 0).getBoolean("recording", false);
    }

}
