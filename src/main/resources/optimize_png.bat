for /f "delims=?" %%i  in ('dir /b /s *.png') do (
	optipng -o 7 "%%i%
	oxipng "%%i" --out "%%i" --opt max --strip safe --zopfli -t 20
)