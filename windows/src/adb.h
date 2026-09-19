#pragma once

#include <windows.h>
#include <string>

#include "logger.h"

namespace adb
{
	/*
	* Get the local directory of the program
	*/
	inline std::string dir()
	{
		char buffer[MAX_PATH];
		GetModuleFileNameA(NULL, buffer, MAX_PATH);

		std::string path(buffer);
		return path.substr(0, path.find_last_of("\\/"));
	}

	/*
	* Reverse the given tcp port.
	* adb reverse tcp:<port> tcp:<port>
	*/
	inline bool reverse(int port)
	{
		std::string path = dir();
		std::string command = "\"" + path + "\\adb\\adb.exe\" reverse tcp:" + std::to_string(port) + " tcp:" + std::to_string(port);

		if (system(command.c_str()) == 0)
		{
			logger << "[ADB:" << port << "] Started reverse " << std::endl;
			return true;
		}
		else
		{
			logger << "[ADB:" << port << "] Failed to start reverse " << std::endl;
			return false;
		}
	}

	/*
	* Forwards a given tcp port
	* adb forward tcp:<port> tcp:<port>
	*/
	inline bool forward(int port)
	{
		std::string path = dir();
		std::string command = "\"" + path + "\\adb\\adb.exe\" forward tcp:" + std::to_string(port) + " tcp:" + std::to_string(port);

		if (system(command.c_str()) == 0)
		{
			logger << "[ADB:" << port << "] Started forward " << std::endl;
			return true;
		}
		else
		{
			logger << "[ADB:" << port << "] Failed to start forward " << std::endl;
			return false;
		}
	}

	/*
	* Removes the reversed/forwarded tcp port mapping.
	* Selectively removes port forwarding without killing the global system ADB server.
	*/
	inline bool kill(int port)
	{
		std::string path = dir();
		std::string reverseCmd = "\"" + path + "\\adb\\adb.exe\" reverse --remove tcp:" + std::to_string(port);
		system(reverseCmd.c_str());

		std::string forwardCmd = "\"" + path + "\\adb\\adb.exe\" forward --remove tcp:" + std::to_string(port);
		system(forwardCmd.c_str());

		logger << "[ADB:" << port << "] Port mapping removed " << std::endl;
		return true;
	}
}